package com.kachat.app.services

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.MessageEntity
import com.kachat.app.models.displayName
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.CallCodec
import com.kachat.app.util.CallEnvelope
import com.kachat.app.util.MessageProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice and video calls between two KaChat contacts, carried by Nextcloud Talk and WebRTC and
 * never leaving the app. Mirrors iOS's CallService.
 *
 * How a call works: ONE side's Nextcloud (with Talk and calls enabled) gets a throwaway public
 * conversation; its token goes to the other side inside an ordinary encrypted 1:1 message
 * ([CallEnvelope.Invite]), that side joins the conversation as a Talk GUEST, and the two phones
 * negotiate one WebRTC peer connection over Talk's internal signaling channel. The host does not
 * have to be the one who tapped Call: a phone without Nextcloud sends [CallEnvelope.Request]
 * and the contact's phone hosts, answering with an invite (`viaRequest`) the requester joins as a
 * guest while the HOST's phone is the one ringing. If neither side can host, the contact answers
 * `call_response {accepted:false, reason:"no_host"}` at once.
 *
 * One call at a time. [session] is the whole UI state; MainActivity shows the call screen off
 * it, the 1:1 thread offers the button whenever the contact's "Allow calls" switch is on.
 */
@Singleton
class CallService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val walletService: WalletService,
    private val walletManager: WalletManager,
    private val nextcloudService: NextcloudService,
    private val knsService: KnsService,
    /** Lazy: the push manager reaches back into the chat repository, which owns this service. */
    private val pushRegistration: dagger.Lazy<PushRegistrationManager>,
    private val incomingCallNotifier: IncomingCallNotifier,
    private val cameraPreview: CallCameraPreview,
) {
    companion object {
        private const val TAG = "CallService"
        /** How long an outgoing call rings before giving up - a phone's own calls give up after
         *  about half a minute; the chat adds a few seconds of delivery lag on top. */
        private const val RING_TIMEOUT_MS = 35_000L
        /** How long the callee's phone rings before the call counts as missed. */
        private const val INCOMING_RING_MS = 30_000L
        /** How long an invite or request stays answerable after it was mined (an old one from a
         *  closed app must not ring hours later). */
        private const val INVITE_FRESHNESS_MS = 45_000L
        /** How long after the media connects the call checks that it really has the phone's
         *  audio - long enough for whatever else was holding it to let go. */
        private const val AUDIO_WATCHDOG_MS = 2_500L
        /** A ring push is only worth sending while the phone is still ringing. */
        private const val RING_PUSH_TIMEOUT_MS = 15_000L
        /** How long a call woken by a push waits for the wallet and the Talk probe behind the
         *  decision to ring: eighty steps of 100 ms, so eight seconds at worst. */
        private const val READY_WAIT_STEPS = 80
        private const val READY_WAIT_STEP_MS = 100L
    }

    sealed class Phase {
        /** Caller: invite or request sent, waiting for the contact to pick up. */
        data object RingingOut : Phase()
        /** Callee: invite or request received, the phone is ringing. */
        data object RingingIn : Phase()
        /** Both sides are in the Talk call and the media is being negotiated. */
        data object Connecting : Phase()
        data object Connected : Phase()
        /** Over; [reason] is the screen's wording key: hangup, declined, no_answer, missed,
         *  remote_hangup, failed, busy, no_host. */
        data class Ended(val reason: String) : Phase()
    }

    /** The live call as the screen sees it. Immutable; every change publishes a new copy. */
    data class ActiveCall(
        val id: String,
        val contact: ContactEntity,
        val isOutgoing: Boolean,
        /** The Talk server and room. Unknown (null / empty) while a call we asked the contact
         *  to host is still waiting for their invite. */
        val server: String?,
        val token: String,
        /** Whether THIS device owns the Talk room (its own Nextcloud): the outgoing side of a
         *  hosted call, or the incoming side of a call the contact asked us to host. The owner
         *  joins with its account and deletes the room at the end; the other side is a guest. */
        val hostsThisCall: Boolean,
        val video: Boolean,
        val phase: Phase,
        val connectedAtMs: Long? = null,
        val isMuted: Boolean = false,
        /** What the phone is actually doing: true while sound comes out of the loudspeaker.
         *  Read from the audio route itself once audio runs, so the button never claims a state
         *  the hardware is not in. */
        val isSpeakerOn: Boolean = video,
        /** What the user asked for (video calls start on the speaker); applied whenever the
         *  audio comes up, and what [isSpeakerOn] converges to. */
        val speakerRequested: Boolean = video,
        val isCameraOff: Boolean = false,
        val remoteVideoTrack: VideoTrack? = null,
        val localVideoTrack: VideoTrack? = null,
        val statusDetail: String? = null,
    )

    /** Everything about the live call the screen does not need: the Talk client, the peer
     *  connection, the timers. Lives and dies with one call. */
    private class Plumbing(val callId: String) {
        var client: NextcloudTalkClient? = null
        var mySessionId: String? = null
        val mySid: String = UUID.randomUUID().toString().take(8)
        var peerSessionId: String? = null
        var peerSid: String? = null
        var webrtc: WebRTCClient? = null
        val pendingCandidates = mutableListOf<IceCandidate>()
        var pullJob: Job? = null
        var timeoutJob: Job? = null
        var offerFallbackJob: Job? = null
        var ringJob: Job? = null
        var sawPeerInCall = false
        var ringtone: Ringtone? = null
        /** What the caller hears while the other phone rings. */
        val ringback = CallRingback()
        /** Whether this side's opening message (invite / request) has gone out. The closing
         *  call_end is only ever sent by the side that started the call, and only after its
         *  opening message did - so a call that failed before ringing costs nothing. */
        var openingMessageSent = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _session = MutableStateFlow<ActiveCall?>(null)
    val session: StateFlow<ActiveCall?> = _session.asStateFlow()
    /** A one-line reason the last attempt failed, for a toast in the chat. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private var plumbing: Plumbing? = null

    /** The call screen is tucked away: the user is elsewhere in the app while the call goes on.
     *  [restore] brings it back, and a call that ends clears it. */
    private val _isMinimized = MutableStateFlow(false)
    val isMinimized: StateFlow<Boolean> = _isMinimized.asStateFlow()

    /** The call is floating in the system's picture-in-picture window: the other person's video
     *  over whatever else is on screen, KaChat included. Set by the activity, which is the only
     *  thing that can know. */
    private val _isInPictureInPicture = MutableStateFlow(false)
    val isInPictureInPicture: StateFlow<Boolean> = _isInPictureInPicture.asStateFlow()

    fun setInPictureInPicture(active: Boolean) {
        _isInPictureInPicture.value = active
        if (active) _isMinimized.value = true
    }

    /** Whether there is a live video call with the other side's picture already arriving - the
     *  only thing worth floating in a small window. */
    val canFloatVideo: Boolean
        get() = _session.value?.let { it.video && it.remoteVideoTrack != null && it.phase !is Phase.Ended } == true

    /** The stand-in camera the call screen shows while a video call rings out. */
    val ringOutCamera: CallCameraPreview get() = cameraPreview

    init {
        // The ringback belongs to ringing out and to nothing else: the moment the call is
        // answered, declined, given up on, or the other phone turns up in the room, it stops.
        // Watching the phase means every one of those paths is covered by itself. Declared after
        // the state it reads, so the collector cannot start before there is anything to collect.
        scope.launch {
            _session.collect { call ->
                if (call == null || call.phase != Phase.RingingOut) plumbing?.ringback?.stop()
            }
        }
    }

    private fun update(transform: (ActiveCall) -> ActiveCall) {
        _session.value = _session.value?.let(transform)
    }

    /**
     * Every call id this device has already rung for, placed, answered, or seen end - newest
     * last, capped. An invite rings at most once per device: an invite is an ordinary on-chain
     * message and the ingest paths can re-deliver recent messages, so without this a call that
     * was over rang again on every reopen.
     */
    private val handledCalls = context.getSharedPreferences("kachat_calls", Context.MODE_PRIVATE)

    private fun hasHandled(callId: String): Boolean =
        handledCalls.getString("handled", "").orEmpty().split(',').contains(callId)

    private fun markHandled(callId: String) {
        val ids = handledCalls.getString("handled", "").orEmpty().split(',').filter { it.isNotEmpty() && it != callId }
        handledCalls.edit().putString("handled", (ids + callId).takeLast(200).joinToString(",")).apply()
    }

    // ---- Availability ----

    /** Whether calls are allowed with this contact - the per-contact switch, OFF by default.
     *  The call button shows regardless; tapping it on a contact that is not yet enabled asks
     *  first. This is the only gate: a phone with no Nextcloud of its own can still start a call
     *  by asking the contact to host it, so hosting ability is not required here. */
    fun canCall(contact: ContactEntity?): Boolean = contact?.callsEnabled == true

    /** Whether this device can open a Talk room itself: a connected Nextcloud with Talk calls
     *  enabled. */
    val canHost: Boolean
        get() = nextcloudService.account.value != null && nextcloudService.talkCallsAvailable.value

    // ---- Outgoing ----

    fun startCall(contact: ContactEntity, video: Boolean) {
        if (_session.value != null) return
        _isMinimized.value = false
        if (contact.callsEnabled != true) return
        _lastError.value = null
        val callId = UUID.randomUUID().toString().lowercase()
        markHandled(callId)
        val account = nextcloudService.account.value
        if (!canHost || account == null) {
            // No Nextcloud here: ask the contact to host. Their phone opens the room and rings
            // (if their "Allow calls" switch is on for us) and answers with an invite this call
            // joins as a guest - see handleIncoming(Invite).
            val pipes = Plumbing(callId)
            plumbing = pipes
            _session.value = ActiveCall(id = callId, contact = contact, isOutgoing = true, server = null, token = "", hostsThisCall = false, video = video, phase = Phase.RingingOut)
            CallDiagnostics.log(TAG, "outgoing ${if (video) "video" else "voice"} call $callId to ${contact.id.takeLast(8)} - asking them to host")
            if (video) cameraPreview.start()
            CallForegroundService.start(context, contact.displayName, video)
            scope.launch {
                try {
                    val payloadHex = sendCallMessage(contact.id, CallCodec.encode(CallEnvelope.Request(callId, video)))
                    pipes.openingMessageSent = true
                    requestRing(contact.id, callId, video, kind = "request", payloadHex = payloadHex)
                    if (plumbing === pipes && _session.value?.phase == Phase.RingingOut) pipes.ringback.start()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _lastError.value = e.message ?: "Call failed"
                    finish("failed")
                    return@launch
                }
                pipes.timeoutJob = scope.launch {
                    delay(RING_TIMEOUT_MS)
                    if (plumbing === pipes && _session.value?.phase == Phase.RingingOut) finish("no_answer")
                }
            }
            return
        }
        val server = account.server.trimEnd('/')
        // Token is filled in once the conversation exists; the screen shows "calling" meanwhile.
        val pipes = Plumbing(callId)
        plumbing = pipes
        _session.value = ActiveCall(id = callId, contact = contact, isOutgoing = true, server = server, token = "", hostsThisCall = true, video = video, phase = Phase.RingingOut)
        CallDiagnostics.log(TAG, "outgoing ${if (video) "video" else "voice"} call $callId to ${contact.id.takeLast(8)} hosted on $server")
        if (video) cameraPreview.start()
        CallForegroundService.start(context, contact.displayName, video)

        scope.launch {
            val client = NextcloudTalkClient(server, NextcloudTalkClient.Auth.Basic(account.username, account.appPassword))
            pipes.client = client
            try {
                val token = withContext(Dispatchers.IO) { client.createPublicConversation("KaChat call with ${contact.displayName}") }
                if (plumbing !== pipes) { withContext(Dispatchers.IO) { client.deleteConversation(token) }; return@launch }
                update { it.copy(token = token) }
                joinAndSignal(pipes)
                val payloadHex = sendCallMessage(contact.id, CallCodec.encode(CallEnvelope.Invite(callId = callId, server = server, token = token, video = video)))
                pipes.openingMessageSent = true
                requestRing(contact.id, callId, video, kind = "invite", payloadHex = payloadHex)
                if (plumbing === pipes && _session.value?.phase == Phase.RingingOut) pipes.ringback.start()
                pipes.timeoutJob = scope.launch {
                    delay(RING_TIMEOUT_MS)
                    if (plumbing === pipes && _session.value?.phase == Phase.RingingOut) finish("no_answer")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Starting call failed", e)
                CallDiagnostics.log(TAG, "start failed: ${e.message}")
                _lastError.value = e.message ?: "Call failed"
                finish("failed")
            }
        }
    }

    // ---- Incoming (driven by ChatRepository's message ingest) ----

    fun handleIncoming(envelope: CallEnvelope, contactAddress: String, blockTimeMs: Long, isOutgoing: Boolean) {
        if (isOutgoing) return
        val fresh = System.currentTimeMillis() - blockTimeMs < INVITE_FRESHNESS_MS
        scope.launch {
            // The app may be running only because a push woke it for this very call, with the
            // Nextcloud account and the Talk probe behind "can this device host?" still on their
            // way. Deciding now would turn the call away as unhostable a second before the
            // answer arrives. Freshness was taken above, at arrival, so waiting cannot make a
            // call look newer than it is.
            waitUntilReadyForCalls()
            when (envelope) {
                is CallEnvelope.Request -> handleRequest(envelope, contactAddress, fresh)
                is CallEnvelope.Invite -> handleInvite(envelope, contactAddress, fresh)
                is CallEnvelope.Response -> {
                    // A response or an end for a call this device is not on means that call is
                    // over; remember it so its invite, arriving later in the same batch, stays quiet.
                    markHandled(envelope.callId)
                    val call = _session.value ?: return@launch
                    if (call.id != envelope.callId || !call.isOutgoing) return@launch
                    if (envelope.accepted) {
                        if (call.phase == Phase.RingingOut) update { it.copy(phase = Phase.Connecting) }
                    } else {
                        finish(if (envelope.reason == "no_host") "no_host" else "declined")
                    }
                }
                is CallEnvelope.End -> {
                    markHandled(envelope.callId)
                    val call = _session.value ?: return@launch
                    if (call.id != envelope.callId) return@launch
                    finish(if (call.phase == Phase.RingingIn) "missed" else "remote_hangup")
                }
            }
        }
    }

    /** Why a call that reached this device never rang - the first question to ask of a call
     *  that "did nothing", and the answer is in the diagnostics archive. */
    private fun dropped(callId: String, why: String) {
        CallDiagnostics.log(TAG, "call ${callId.take(8)} not rung: $why")
    }

    /**
     * Waits, briefly, for the things a call is judged against: a wallet to read contacts with,
     * and the answer to "can this device host a call?". A push that starts the app races both,
     * and the capabilities probe behind the second one is a network round trip.
     *
     * Returns as soon as there is nothing left to wait for: no wallet at all, no Nextcloud
     * account (so hosting was never possible), or a probe that has answered - including from
     * what it answered last time, which is remembered per wallet.
     */
    private suspend fun waitUntilReadyForCalls() {
        var waited = 0
        while (waited < READY_WAIT_STEPS) {
            val hasWallet = runCatching { walletManager.hasWallet() }.getOrDefault(false)
            if (!hasWallet) {
                delay(READY_WAIT_STEP_MS)
                waited++
                continue
            }
            if (nextcloudService.account.value == null) return
            if (nextcloudService.talkCallsAvailable.value) return
            if (nextcloudService.talkAvailabilityReason.value != "not probed yet") return
            delay(READY_WAIT_STEP_MS)
            waited++
        }
        if (waited >= READY_WAIT_STEPS) {
            CallDiagnostics.log(TAG, "still not ready to judge a call after ${READY_WAIT_STEPS * READY_WAIT_STEP_MS / 1000}s - deciding anyway")
        }
    }

    /** The contact has no Nextcloud and asks us to host their call - only if their "Allow calls"
     *  switch is on and this device can host; if it cannot, say so at once with no_host. */
    private suspend fun handleRequest(request: CallEnvelope.Request, contactAddress: String, fresh: Boolean) {
        val id = request.callId
        if (hasHandled(id)) { dropped(id, "already handled"); return }
        val contact = runCatching { chatRepository.getContact(contactAddress) }.getOrNull()
        if (contact == null) { dropped(id, "unknown sender"); return }
        if (contact.callsEnabled != true) { dropped(id, "calls not enabled for this contact"); return }
        if (!fresh) { dropped(id, "stale"); return }
        val account = nextcloudService.account.value
        if (!canHost || account == null) {
            // Neither side can host. Nothing goes on chain from this side - the caller is the
            // only one who pays for a call - so the requester's ring-out is what tells them one
            // of the two needs Nextcloud Talk.
            markHandled(request.callId)
            CallDiagnostics.log(TAG, "request ${request.callId} from ${contact.id.takeLast(8)}: cannot host, staying silent")
            return
        }
        // Busy: silent, and the caller rings out. Same request delivered twice: ignored.
        if (_session.value != null) { dropped(id, "busy"); return }
        markHandled(request.callId)
        val server = account.server.trimEnd('/')
        val pipes = Plumbing(request.callId)
        plumbing = pipes
        CallDiagnostics.log(TAG, "request ${request.callId} from ${contact.id.takeLast(8)}: hosting on $server")
        _isMinimized.value = false
        _session.value = ActiveCall(id = request.callId, contact = contact, isOutgoing = false, server = server, token = "", hostsThisCall = true, video = request.video, phase = Phase.RingingIn)
        startRinging(pipes)
        pipes.timeoutJob = scope.launch {
            delay(INCOMING_RING_MS)
            if (plumbing === pipes && _session.value?.phase == Phase.RingingIn) finish("missed")
        }
        // Open the room now so the requester can already be waiting in it as a guest when we
        // accept; we join the call itself on accept.
        scope.launch {
            val client = NextcloudTalkClient(server, NextcloudTalkClient.Auth.Basic(account.username, account.appPassword))
            pipes.client = client
            try {
                val token = withContext(Dispatchers.IO) { client.createPublicConversation("KaChat call with ${contact.displayName}") }
                if (plumbing !== pipes) { withContext(Dispatchers.IO) { client.deleteConversation(token) }; return@launch }
                update { it.copy(token = token) }
                sendCallMessage(contact.id, CallCodec.encode(CallEnvelope.Invite(callId = request.callId, server = server, token = token, video = request.video, viaRequest = true)))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Hosting a requested call failed", e)
                CallDiagnostics.log(TAG, "hosting failed: ${e.message}")
                _lastError.value = e.message ?: "Call failed"
                finish("failed")
            }
        }
    }

    private suspend fun handleInvite(invite: CallEnvelope.Invite, contactAddress: String, fresh: Boolean) {
        val server = invite.server.trimEnd('/')
        if (!server.startsWith("https://", ignoreCase = true)) return
        // The contact hosting the call WE asked for: this invite answers our request, so join it
        // straight away as a guest - their phone is the one ringing.
        val current = _session.value
        val pipes = plumbing
        if (current != null && pipes != null && current.isOutgoing && !current.hostsThisCall &&
            current.id == invite.callId && current.phase == Phase.RingingOut
        ) {
            pipes.timeoutJob?.cancel()
            update { it.copy(server = server, token = invite.token, phase = Phase.Connecting) }
            CallDiagnostics.log(TAG, "call ${invite.callId}: contact is hosting on $server, joining as guest")
            pipes.client = NextcloudTalkClient(server, NextcloudTalkClient.Auth.Guest)
            try {
                joinAndSignal(pipes)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Joining the hosted call failed", e)
                CallDiagnostics.log(TAG, "joining hosted call failed: ${e.message}")
                _lastError.value = e.message ?: "Call failed"
                finish("failed")
            }
            return
        }
        // Once per call id, ever. A re-ingested invite for a call that already rang (or already
        // ended) is history, not a phone ringing.
        val id = invite.callId
        if (hasHandled(id)) { dropped(id, "already handled"); return }
        val contact = runCatching { chatRepository.getContact(contactAddress) }.getOrNull()
        if (contact == null) { dropped(id, "unknown sender"); return }
        if (contact.callsEnabled != true) { dropped(id, "calls not enabled for this contact"); return }
        if (!fresh) { dropped(id, "stale"); return }
        // Already on a call: stay silent and let the caller ring out. This same invite
        // delivered twice is simply ignored.
        if (current != null) { dropped(id, "busy"); return }
        markHandled(invite.callId)
        val incoming = Plumbing(invite.callId)
        plumbing = incoming
        CallDiagnostics.log(TAG, "incoming ${if (invite.video) "video" else "voice"} call ${invite.callId} from ${contact.id.takeLast(8)} via $server")
        _isMinimized.value = false
        _session.value = ActiveCall(id = invite.callId, contact = contact, isOutgoing = false, server = server, token = invite.token, hostsThisCall = false, video = invite.video, phase = Phase.RingingIn)
        startRinging(incoming)
        incoming.timeoutJob = scope.launch {
            delay(INCOMING_RING_MS)
            if (plumbing === incoming && _session.value?.phase == Phase.RingingIn) finish("missed")
        }
    }

    fun acceptIncoming() {
        val call = _session.value ?: return
        val pipes = plumbing ?: return
        if (call.phase != Phase.RingingIn) return
        stopRinging(pipes)
        pipes.timeoutJob?.cancel()
        update { it.copy(phase = Phase.Connecting) }
        CallForegroundService.start(context, call.contact.displayName, call.video)
        scope.launch {
            if (call.hostsThisCall) {
                // A call the contact asked us to host: the room was opened when it rang and the
                // owning client already exists. Wait for the room if the invite is still on its
                // way out.
                var waited = 0
                while (_session.value?.token.isNullOrEmpty() && waited < 100 && plumbing === pipes) {
                    delay(100)
                    waited++
                }
                if (plumbing !== pipes || _session.value?.token.isNullOrEmpty() || pipes.client == null) {
                    finish("failed")
                    return@launch
                }
            } else {
                val server = call.server ?: return@launch
                pipes.client = NextcloudTalkClient(server, NextcloudTalkClient.Auth.Guest)
            }
            try {
                // Turning up in the Talk call is the answer; nothing goes on chain from here.
                joinAndSignal(pipes)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Joining call failed", e)
                CallDiagnostics.log(TAG, "join failed: ${e.message}")
                _lastError.value = e.message ?: "Call failed"
                finish("failed")
            }
        }
    }

    fun declineIncoming() {
        val call = _session.value ?: return
        val pipes = plumbing ?: return
        if (call.phase != Phase.RingingIn) return
        stopRinging(pipes)
        val server = call.server
        val token = call.token
        if (!call.hostsThisCall && server != null && token.isNotEmpty()) {
            // Tell the caller through Talk, not the chain: join their room as a guest, hand
            // their session one "kachat_decline", and leave. Best effort - if it fails the
            // caller simply rings out.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val client = NextcloudTalkClient(server, NextcloudTalkClient.Auth.Guest)
                    val sessionId = client.joinConversation(token)
                    client.pullSignaling(token, sessionId)
                        .filterIsInstance<NextcloudTalkClient.SignalingEvent.UsersInRoom>()
                        .flatMap { it.users }
                        .filter { it.sessionId != sessionId }
                        .forEach { user ->
                            val message = NextcloudTalkClient.PeerMessage(to = user.sessionId, sid = "decline", roomType = "video", type = "kachat_decline", payload = JSONObject())
                            runCatching { client.sendSignaling(token, sessionId, listOf(message)) }
                        }
                    client.leaveConversation(token)
                }
            }
        }
        // A hosted request we are declining: finish deletes the room, and the requester waiting
        // in it reads the 404 as the decline.
        scope.launch { finish("declined") }
    }

    fun hangUp() {
        val call = _session.value ?: return
        if (call.phase is Phase.Ended) return
        scope.launch { finish(if (call.isOutgoing && call.phase == Phase.RingingOut) "cancelled" else "hangup") }
    }

    /** Clears an ended call off the screen. */
    fun dismissEnded() {
        if (_session.value?.phase is Phase.Ended) {
            _session.value = null
            plumbing = null
            _isMinimized.value = false
        }
    }

    // ---- In-call controls ----

    fun toggleMute() {
        val call = _session.value ?: return
        val muted = !call.isMuted
        update { it.copy(isMuted = muted) }
        plumbing?.webrtc?.setMuted(muted)
    }

    fun toggleSpeaker() {
        val call = _session.value ?: return
        // Flip from where the audio actually is, not from where we last asked it to be.
        val on = !call.isSpeakerOn
        update { it.copy(isSpeakerOn = on, speakerRequested = on) }
        plumbing?.webrtc?.setSpeaker(on)
        // The route-change callback settles the displayed state a moment later.
    }

    /**
     * The phone's audio route moved (the speaker button, headphones, Bluetooth, another app
     * handing audio back): show where the sound really comes out. Only once there is a peer
     * connection - before that the route says nothing about this call.
     */
    private fun routeChanged() {
        val webrtc = plumbing?.webrtc ?: return
        val onSpeaker = webrtc.isOnSpeaker ?: return
        if (_session.value?.isSpeakerOn != onSpeaker) update { it.copy(isSpeakerOn = onSpeaker) }
    }

    /**
     * Makes sure the call still has the phone's audio. Sound goes missing when something else
     * held it at the moment the call was answered, or took it and never handed it back; either
     * way, take it again and put the route back where the user asked for it.
     */
    private fun ensureAudioRunning(reason: String) {
        val call = _session.value ?: return
        if (call.phase != Phase.Connecting && call.phase != Phase.Connected) return
        val webrtc = plumbing?.webrtc ?: return
        CallDiagnostics.log(TAG, "re-asserting call audio ($reason)")
        webrtc.ensureAudioRunning(call.speakerRequested)
        routeChanged()
    }

    fun toggleCamera() {
        val call = _session.value ?: return
        if (!call.video) return
        val off = !call.isCameraOff
        update { it.copy(isCameraOff = off) }
        plumbing?.webrtc?.setVideoEnabled(!off)
        val client = plumbing?.client ?: return
        scope.launch(Dispatchers.IO) { client.updateCallFlags(call.token, video = !off) }
    }

    /**
     * Turns the voice call into a video call, for both sides, without anyone hanging up: our
     * camera goes on, the other phone is told to turn its own on, and the connection is
     * renegotiated with the new tracks.
     */
    fun upgradeToVideo() {
        val call = _session.value ?: return
        val pipes = plumbing ?: return
        if (call.video) return
        if (call.phase != Phase.Connecting && call.phase != Phase.Connected) return
        if (!turnOnVideo(pipes)) return
        scope.launch {
            // Order matters, and the channel keeps it: the other side turns its camera on
            // first, so its answer to the offer that follows already carries its video.
            send(pipes, "kachat_video_upgrade", JSONObject())
            sendOffer(pipes)
        }
    }

    /** Our own half of the switch to video: the camera on, the speaker on, the room told. */
    private fun turnOnVideo(pipes: Plumbing): Boolean {
        val call = _session.value ?: return false
        if (call.video || plumbing !== pipes) return false
        val webrtc = pipes.webrtc ?: return false
        val track = webrtc.enableVideo() ?: return false
        update { it.copy(video = true, isCameraOff = false, speakerRequested = true, isSpeakerOn = true, localVideoTrack = track) }
        webrtc.setSpeaker(true)
        CallDiagnostics.log(TAG, "call ${call.id}: switched to video")
        val client = pipes.client
        if (client != null) {
            scope.launch(Dispatchers.IO) { runCatching { client.updateCallFlags(call.token, video = true) } }
        }
        return true
    }

    /**
     * Puts the call screen away while the call goes on, and brings it back. The call itself is
     * untouched: this is only about what is on screen.
     */
    fun minimize() {
        val call = _session.value ?: return
        if (call.phase is Phase.Ended) return
        _isMinimized.value = true
    }

    fun restore() {
        if (_session.value != null) _isMinimized.value = false
    }

    fun flipCamera() {
        plumbing?.webrtc?.flipCamera()
    }

    // ---- Talk + WebRTC plumbing ----

    /** Joins the conversation and the call, brings up the peer connection, and starts the
     *  signaling pull loop. Shared by every direction; only the client (own account vs guest)
     *  differs. */
    private suspend fun joinAndSignal(pipes: Plumbing) {
        val client = pipes.client ?: return
        val call = _session.value ?: return
        // Only one thing can hold the camera: take it back from the ring-out preview before the
        // call opens it for real.
        if (call.video) cameraPreview.stop()
        val settings = withContext(Dispatchers.IO) { client.signalingSettings(call.token) }
        if (settings.mode.equals("external", ignoreCase = true)) throw NextcloudTalkClient.externalSignalingUnsupported()
        val sessionId = withContext(Dispatchers.IO) { client.joinConversation(call.token) }
        pipes.mySessionId = sessionId
        if (client.auth is NextcloudTalkClient.Auth.Guest) {
            val name = ownDisplayName()
            withContext(Dispatchers.IO) { client.setGuestDisplayName(call.token, name) }
        }
        withContext(Dispatchers.IO) { client.joinCall(call.token, call.video) }

        val webrtc = WebRTCClient(context, settings.iceServers, call.video)
        pipes.webrtc = webrtc
        update { it.copy(localVideoTrack = webrtc.localVideoTrack) }
        webrtc.onLocalCandidate = { candidate ->
            scope.launch {
                send(pipes, "candidate", JSONObject().put("candidate", JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid ?: "")
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)))
            }
        }
        webrtc.onRemoteVideoTrack = { track -> scope.launch { if (plumbing === pipes) update { it.copy(remoteVideoTrack = track) } } }
        webrtc.onConnectionState = { state ->
            scope.launch {
                if (plumbing !== pipes) return@launch
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        val firstTime = _session.value?.connectedAtMs == null
                        update { it.copy(phase = Phase.Connected, connectedAtMs = it.connectedAtMs ?: System.currentTimeMillis(), statusDetail = null) }
                        routeChanged()
                        if (firstTime) {
                            // Audio watchdog: media is flowing, so a couple of seconds from now
                            // sound must be too. If something else was holding the phone's audio
                            // when the call was answered, take it now.
                            scope.launch {
                                delay(AUDIO_WATCHDOG_MS)
                                if (plumbing === pipes) ensureAudioRunning("watchdog")
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> update { it.copy(statusDetail = "Reconnecting") }
                    PeerConnection.IceConnectionState.FAILED -> finish("failed")
                    else -> {}
                }
            }
        }
        webrtc.onAudioRouteChanged = { scope.launch { if (plumbing === pipes) routeChanged() } }
        webrtc.onAudioFocusRegained = { scope.launch { if (plumbing === pipes) ensureAudioRunning("audio focus returned") } }
        webrtc.setSpeaker(_session.value?.speakerRequested ?: call.video)
        routeChanged()
        if (call.video) webrtc.startCaptureIfNeeded()

        pipes.pullJob = scope.launch { pullLoop(pipes) }
    }

    private suspend fun pullLoop(pipes: Plumbing) {
        val client = pipes.client ?: return
        val sessionId = pipes.mySessionId ?: return
        val token = _session.value?.token ?: return
        while (scope.isActive && plumbing === pipes) {
            try {
                val events = withContext(Dispatchers.IO) { client.pullSignaling(token, sessionId) }
                if (plumbing !== pipes) return
                for (event in events) handle(event, pipes)
            } catch (e: CancellationException) {
                return
            } catch (e: NextcloudTalkClient.TalkException) {
                when (e.kind) {
                    NextcloudTalkClient.TalkException.Kind.CONVERSATION_GONE, NextcloudTalkClient.TalkException.Kind.SESSION_LOST -> {
                        Log.i(TAG, "Signaling ended: ${e.message}")
                        // A room that disappears before the call connected, while we are the
                        // guest, is the host declining our request.
                        val call = _session.value
                        val declined = call != null && !call.hostsThisCall && call.connectedAtMs == null
                        finish(if (declined) "declined" else "remote_hangup")
                        return
                    }
                    else -> delay(3_000)
                }
            } catch (e: Exception) {
                if (plumbing !== pipes) return
                delay(3_000)
            }
        }
    }

    private suspend fun handle(event: NextcloudTalkClient.SignalingEvent, pipes: Plumbing) {
        when (event) {
            is NextcloudTalkClient.SignalingEvent.UsersInRoom -> {
                val mine = pipes.mySessionId ?: ""
                val others = event.users.filter { it.sessionId != mine && it.inCall != 0 }
                val peer = others.firstOrNull()
                if (pipes.peerSessionId == null && peer != null) {
                    pipes.peerSessionId = peer.sessionId
                    pipes.sawPeerInCall = true
                    update { if (it.phase == Phase.RingingOut || it.phase == Phase.RingingIn) it.copy(phase = Phase.Connecting) else it }
                    pipes.timeoutJob?.cancel()
                    // "Larger session ids call smaller ones" - the same tie-break the Talk web
                    // client uses, so exactly one side offers. The other side still offers itself
                    // if nothing arrives within ten seconds, in case the first offer was lost.
                    if (peer.sessionId < mine) {
                        sendOffer(pipes)
                    } else {
                        pipes.offerFallbackJob = scope.launch {
                            delay(10_000)
                            if (plumbing === pipes && pipes.webrtc?.hasRemoteDescription == false) sendOffer(pipes)
                        }
                    }
                } else if (pipes.peerSessionId != null && pipes.sawPeerInCall && others.none { it.sessionId == pipes.peerSessionId }) {
                    // The other side left the call (hung up, or their app died).
                    finish("remote_hangup")
                }
            }
            is NextcloudTalkClient.SignalingEvent.Message -> {
                val data = event.data
                val from = data.optString("from").ifEmpty { return }
                val type = data.optString("type").ifEmpty { return }
                if (type == "kachat_decline") {
                    // The callee said no through Talk (no chain message from their side).
                    val call = _session.value
                    if (call != null && call.isOutgoing && call.connectedAtMs == null) finish("declined")
                    return
                }
                if (type == "kachat_video_upgrade") {
                    // The other side switched to video: turn ours on too, before their offer
                    // arrives, so the answer we send already carries our camera.
                    turnOnVideo(pipes)
                    return
                }
                if (pipes.peerSessionId == null) pipes.peerSessionId = from
                if (from != pipes.peerSessionId) return
                val webrtc = pipes.webrtc ?: return
                val payload = data.optJSONObject("payload") ?: JSONObject()
                when (type) {
                    "offer" -> {
                        pipes.peerSid = data.optString("sid").ifEmpty { null }
                        pipes.offerFallbackJob?.cancel()
                        val sdp = payload.optString("sdp").ifEmpty { return }
                        try {
                            webrtc.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
                            flushCandidates(pipes)
                            val answer = webrtc.createAnswer()
                            send(pipes, "answer", JSONObject().put("type", "answer").put("sdp", answer.description).put("nick", ownDisplayName()))
                        } catch (e: Exception) {
                            Log.w(TAG, "Answering offer failed", e)
                        }
                    }
                    "answer" -> {
                        val sdp = payload.optString("sdp").ifEmpty { return }
                        try {
                            webrtc.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                            flushCandidates(pipes)
                        } catch (e: Exception) {
                            Log.w(TAG, "Applying answer failed", e)
                        }
                    }
                    "candidate" -> {
                        val inner = payload.optJSONObject("candidate") ?: return
                        val sdp = inner.optString("candidate").ifEmpty { return }
                        val candidate = IceCandidate(inner.optString("sdpMid").ifEmpty { null }, inner.optInt("sdpMLineIndex", 0), sdp)
                        if (webrtc.hasRemoteDescription) webrtc.addRemoteCandidate(candidate) else pipes.pendingCandidates += candidate
                    }
                }
            }
        }
    }

    private suspend fun sendOffer(pipes: Plumbing) {
        val webrtc = pipes.webrtc ?: return
        try {
            val offer = webrtc.createOffer()
            send(pipes, "offer", JSONObject().put("type", "offer").put("sdp", offer.description).put("nick", ownDisplayName()))
        } catch (e: Exception) {
            Log.w(TAG, "Creating offer failed", e)
        }
    }

    private fun flushCandidates(pipes: Plumbing) {
        val webrtc = pipes.webrtc ?: return
        val queued = pipes.pendingCandidates.toList()
        pipes.pendingCandidates.clear()
        queued.forEach { webrtc.addRemoteCandidate(it) }
    }

    private suspend fun send(pipes: Plumbing, type: String, payload: JSONObject) {
        val client = pipes.client ?: return
        val mine = pipes.mySessionId ?: return
        val peer = pipes.peerSessionId ?: return
        val token = _session.value?.token ?: return
        val message = NextcloudTalkClient.PeerMessage(to = peer, sid = pipes.peerSid ?: pipes.mySid, roomType = "video", type = type, payload = payload)
        try {
            withContext(Dispatchers.IO) { client.sendSignaling(token, mine, listOf(message)) }
        } catch (e: Exception) {
            Log.w(TAG, "Sending $type failed", e)
        }
    }

    // ---- Teardown ----

    private suspend fun finish(reason: String) {
        val call = _session.value ?: return
        val pipes = plumbing ?: return
        if (call.phase is Phase.Ended) return
        CallDiagnostics.log(TAG, "call ${call.id} ends: $reason (phase was ${call.phase})")
        markHandled(call.id)
        stopRinging(pipes)
        pipes.ringback.stop()
        if (call.video) cameraPreview.stop()
        pipes.timeoutJob?.cancel()
        pipes.offerFallbackJob?.cancel()
        pipes.pullJob?.cancel()
        pipes.client?.cancelPull()
        val duration = call.connectedAtMs?.let { ((System.currentTimeMillis() - it) / 1000).toInt() }
        // Order matters. The screen holds sinks on the video tracks; publish the ended state
        // with the tracks gone FIRST and give Compose a frame to detach them, THEN destroy the
        // peer connection - off the main thread, since disposing a factory is slow. Closing
        // first left the renderer detaching from a track whose native side no longer existed,
        // and that call never returned: a ten-second ANR on the main thread.
        update { it.copy(phase = Phase.Ended(reason), remoteVideoTrack = null, localVideoTrack = null) }
        val webrtc = pipes.webrtc
        pipes.webrtc = null
        if (webrtc != null) {
            scope.launch {
                delay(120)
                withContext(Dispatchers.IO) { runCatching { webrtc.close() } }
            }
        }
        CallForegroundService.stop(context)

        // The caller is the only side that ever puts a call on chain: one opening message
        // (invite or request) and one closing message with how it went and, if it connected, for
        // how long. A callee's hang-up reaches the caller through the room instead.
        if (call.isOutgoing && pipes.openingMessageSent) {
            runCatching { sendCallMessage(call.contact.id, CallCodec.encode(CallEnvelope.End(call.id, reason = reason, durationSeconds = duration))) }
        }
        val client = pipes.client
        val token = _session.value?.token.orEmpty()
        if (client != null && token.isNotEmpty()) {
            val owner = call.hostsThisCall
            scope.launch(Dispatchers.IO) {
                client.leaveCall(token)
                client.leaveConversation(token)
                if (owner) client.deleteConversation(token)
            }
        }
        // Let the "Call ended" state show for a moment, then clear the screen.
        scope.launch {
            delay(1_500)
            if (plumbing === pipes && _session.value?.id == call.id) {
                _session.value = null
                plumbing = null
            }
        }
    }

    // ---- Ringing ----

    private fun startRinging(pipes: Plumbing) {
        // The system's own ringing UI: a call notification with a full-screen intent, so a
        // locked or busy phone shows the call screen and Answer / Decline without the app being
        // open. Silent by design - the ringtone and the vibrator below are the one ring.
        _session.value?.let { call ->
            runCatching { incomingCallNotifier.showIncoming(call.id, call.contact.displayName, call.video) }
                .onFailure { Log.w(TAG, "Incoming call notification failed", it) }
        }
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = true
            ringtone.play()
            pipes.ringtone = ringtone
        }.onFailure { Log.w(TAG, "Ringtone failed", it) }
        pipes.ringJob = scope.launch {
            val vibrator = vibrator()
            while (isActive) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), -1))
                    else @Suppress("DEPRECATION") vibrator?.vibrate(longArrayOf(0, 500, 300, 500), -1)
                }
                delay(2_000)
            }
        }
    }

    private fun stopRinging(pipes: Plumbing) {
        incomingCallNotifier.clear()
        pipes.ringJob?.cancel()
        pipes.ringJob = null
        runCatching { pipes.ringtone?.stop() }
        pipes.ringtone = null
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private suspend fun ownDisplayName(): String {
        val address = runCatching { walletManager.getAddress() }.getOrNull() ?: return "KaChat"
        val domain = runCatching { withContext(Dispatchers.IO) { knsService.reverseResolve(address) } }.getOrNull()
        return if (!domain.isNullOrEmpty()) domain else "KaChat ${address.takeLast(6)}"
    }

    /**
     * Asks the push service to ring the callee's phones for this call (PUSH_EXTENSIONS.md §5).
     * A phone with KaChat closed cannot see the chain, so without this it only rings once the app
     * happens to sync. [payloadHex] is the very message that just went on chain, so the push
     * carries nothing the chain does not.
     *
     * Best effort: no push and an open app still rings off the chain message a little later.
     */
    private fun requestRing(contactAddress: String, callId: String, video: Boolean, kind: String, payloadHex: String) {
        if (payloadHex.isEmpty()) return
        scope.launch {
            runCatching {
                // Bounded: the ring is only worth anything while the phone is still ringing, and
                // the push API can be unconfigured, which would otherwise wait for ever.
                kotlinx.coroutines.withTimeout(RING_PUSH_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        pushRegistration.get().requestRing(
                            toAddress = contactAddress,
                            callId = callId,
                            video = video,
                            kind = kind,
                            payloadHex = payloadHex,
                        )
                    }
                }
            }.onFailure { CallDiagnostics.log(TAG, "ring push not sent: ${it.message}") }
        }
    }

    /** One encrypted 1:1 message carrying a call envelope, with its own sent bubble - the
     *  same insert / send / finalize the composer does, so the chat shows the call history.
     *  Returns the on-chain payload hex, which the ring push carries to a sleeping phone. */
    private suspend fun sendCallMessage(contactId: String, payload: String): String {
        val myAddress = walletManager.getAddress()
        val pendingId = "pending_${UUID.randomUUID()}"
        return withContext(Dispatchers.IO) {
            chatRepository.insertMessage(
                MessageEntity(
                    id = pendingId, contactId = contactId, walletAddress = myAddress, type = MessageProtocol.TYPE_COMM,
                    direction = "sent", plaintextBody = payload, encryptedPayload = "", amountSompi = 0,
                    blockTimestamp = System.currentTimeMillis(), deliveryStatus = "pending"
                )
            )
            try {
                val result = walletService.sendKasiaMessage(contactId, payload)
                chatRepository.finalizeProvisionalMessage(
                    pendingId,
                    MessageEntity(
                        id = result.txId, contactId = contactId, walletAddress = myAddress, type = MessageProtocol.TYPE_COMM,
                        direction = "sent", plaintextBody = payload, encryptedPayload = result.payloadHex, amountSompi = 0,
                        blockTimestamp = System.currentTimeMillis(), deliveryStatus = "sent"
                    )
                )
                result.payloadHex
            } catch (e: Exception) {
                chatRepository.updateMessageStatus(pendingId, "failed")
                throw e
            }
        }
    }
}
