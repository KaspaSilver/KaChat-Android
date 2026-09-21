package com.kachat.app.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One peer connection for a KaChat call: local microphone (and camera, for video calls), the
 * remote tracks, and the offer/answer/candidate plumbing [CallService] drives over Talk's
 * signaling channel. Deliberately small - a 1:1 call is one connection, one audio track, at
 * most one video track each way. Mirrors iOS's WebRTCClient on the Android WebRTC library.
 */
class WebRTCClient(private val context: Context, iceServers: List<NextcloudTalkClient.IceServer>, wantsVideo: Boolean) {
    companion object {
        private const val TAG = "WebRTCClient"
        @Volatile private var initialized = false

        /** One shared EGL context: the factory's codecs and every on-screen renderer use it. */
        val eglBase: EglBase by lazy { EglBase.create() }

        private fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }

    private val factory: PeerConnectionFactory
    private val connection: PeerConnection
    private val audioTrack: AudioTrack
    var localVideoTrack: VideoTrack? = null
        private set
    var remoteVideoTrack: VideoTrack? = null
        private set
    /** Whether this connection carries a camera. A voice call turns it on mid-call; see
     *  [enableVideo]. */
    var wantsVideo: Boolean = wantsVideo
        private set
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var usingFrontCamera = true
    private var capturing = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousAudioMode = audioManager.mode
    private var focusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false
    private var routeListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var routeListenerExecutor: java.util.concurrent.ExecutorService? = null

    /** Fired on WebRTC's own threads; CallService hops to its scope. */
    var onLocalCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionState: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    /** The phone moved the sound somewhere else (speaker, earpiece, headphones, Bluetooth), so
     *  the speaker button can show where it really is. */
    var onAudioRouteChanged: (() -> Unit)? = null
    /** Audio came back after something else had taken it. */
    var onAudioFocusRegained: (() -> Unit)? = null

    // Declared before init: the peer connection is created in init and needs it.
    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) { state?.let { onConnectionState?.invoke(it) } }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) { candidate?.let { onLocalCandidate?.invoke(it) } }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {
            val track = stream?.videoTracks?.firstOrNull() ?: return
            remoteVideoTrack = track
            onRemoteVideoTrack?.invoke(track)
        }
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() as? VideoTrack ?: return
            remoteVideoTrack = track
            onRemoteVideoTrack?.invoke(track)
        }
    }

    init {
        ensureInitialized(context)
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val config = PeerConnection.RTCConfiguration(iceServers.map { server ->
            val builder = PeerConnection.IceServer.builder(server.urls)
            if (server.username != null && server.credential != null) {
                builder.setUsername(server.username).setPassword(server.credential)
            }
            builder.createIceServer()
        }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        connection = factory.createPeerConnection(config, observer) ?: error("WebRTC: could not create a peer connection")

        val audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("kachat-audio", audioSource)
        connection.addTrack(audioTrack, listOf("kachat"))

        if (wantsVideo) attachCamera()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { onAudioRouteChanged?.invoke() }
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            runCatching {
                audioManager.addOnCommunicationDeviceChangedListener(executor, listener)
                routeListener = listener
                routeListenerExecutor = executor
            }.onFailure { executor.shutdown() }
        }
    }

    /**
     * Turns a voice call's connection into a video one: adds the camera track - the first offer
     * already carried a receive-capable video line, so there is something for it to attach to -
     * and starts capturing. The caller renegotiates afterwards. Returns the local track.
     */
    fun enableVideo(): VideoTrack? {
        localVideoTrack?.let { return it }
        attachCamera()
        wantsVideo = true
        startCaptureIfNeeded()
        return localVideoTrack
    }

    private fun attachCamera() {
        val videoSource = factory.createVideoSource(false)
        val track = factory.createVideoTrack("kachat-video", videoSource)
        connection.addTrack(track, listOf("kachat"))
        localVideoTrack = track
        surfaceHelper = SurfaceTextureHelper.create("KaChatCapture", eglBase.eglBaseContext)
        capturer = createCapturer()?.also { it.initialize(surfaceHelper, context.applicationContext, videoSource.capturerObserver) }
    }

    private fun createCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context.applicationContext)
        val names = enumerator.deviceNames
        val preferred = names.firstOrNull { enumerator.isFrontFacing(it) == usingFrontCamera } ?: names.firstOrNull() ?: return null
        return enumerator.createCapturer(preferred, null)
    }

    // ---- Media control ----

    fun startCaptureIfNeeded() {
        val capturer = capturer ?: return
        if (capturing) return
        // 720p-ish: enough for a phone screen, kind to the uplink.
        runCatching { capturer.startCapture(1280, 720, 30) }
            .onSuccess { capturing = true }
            .onFailure { Log.w(TAG, "Camera capture failed to start", it) }
    }

    fun stopCapture() {
        if (!capturing) return
        runCatching { capturer?.stopCapture() }
        capturing = false
    }

    fun flipCamera() {
        usingFrontCamera = !usingFrontCamera
        capturer?.switchCamera(null)
    }

    fun setMuted(muted: Boolean) {
        audioTrack.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        if (enabled) startCaptureIfNeeded() else stopCapture()
    }

    /**
     * The audio route: earpiece or speaker. Each step stands alone - a mode the system refuses
     * to change mid-call must not stop the route from moving, and a refused route must not stop
     * the call from holding audio focus.
     */
    fun setSpeaker(speaker: Boolean) {
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { Log.w(TAG, "Audio mode failed: ${it.message}") }
        runCatching { routeTo(speaker) }
            .onFailure { Log.w(TAG, "Audio route change failed: ${it.message}") }
        runCatching { holdAudioFocus() }
            .onFailure { Log.w(TAG, "Audio focus failed: ${it.message}") }
    }

    /** Android 12 moved routing to a device object; older phones keep the speakerphone flag. */
    private fun routeTo(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == wanted }
            if (device != null) {
                audioManager.setCommunicationDevice(device)
                return
            }
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = speaker
    }

    /**
     * Where the sound actually comes out right now, read from the phone rather than from the
     * last thing the app asked for - so the speaker button can never claim a state the hardware
     * is not in. Null while there is nothing to read.
     */
    val isOnSpeaker: Boolean?
        get() = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn
            }
        }.getOrNull()

    /**
     * Whether the sound is on the earpiece - the phone's own receiver, not headphones and not
     * Bluetooth. Those two are the user's doing and are left alone; the earpiece is what the
     * system falls back to when it resets a route the call had asked for.
     */
    val isOnEarpiece: Boolean
        get() = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            } else {
                @Suppress("DEPRECATION")
                !audioManager.isSpeakerphoneOn && !audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn
            }
        }.getOrDefault(false)

    /**
     * Makes sure the call still owns the phone's audio. Sound goes missing when something else
     * took the focus (another app's call, an alarm) and did not hand it back, or when the mode
     * was reset under us; re-asserting all three costs nothing when they are already right.
     */
    fun ensureAudioRunning(speaker: Boolean) {
        setSpeaker(speaker)
    }

    /**
     * Takes the phone's audio for this call, the way a dialler does: everything else pauses,
     * and [onAudioFocusRegained] fires if it comes back after being taken away.
     */
    private fun holdAudioFocus() {
        if (audioFocusHeld) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_GAIN) onAudioFocusRegained?.invoke()
            }
            .build()
        focusRequest = request
        audioFocusHeld = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        audioFocusHeld = false
        runCatching { audioManager.abandonAudioFocusRequest(request) }
    }

    // ---- Negotiation ----

    private fun receiveConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    suspend fun createOffer(): SessionDescription {
        val offer = create { observer -> connection.createOffer(observer, receiveConstraints()) }
        set { observer -> connection.setLocalDescription(observer, offer) }
        return offer
    }

    suspend fun createAnswer(): SessionDescription {
        val answer = create { observer -> connection.createAnswer(observer, receiveConstraints()) }
        set { observer -> connection.setLocalDescription(observer, answer) }
        return answer
    }

    suspend fun setRemoteDescription(description: SessionDescription) {
        set { observer -> connection.setRemoteDescription(observer, description) }
    }

    fun addRemoteCandidate(candidate: IceCandidate) {
        if (!connection.addIceCandidate(candidate)) Log.w(TAG, "Adding remote candidate failed")
    }

    val hasRemoteDescription: Boolean get() = connection.remoteDescription != null

    fun close() {
        stopCapture()
        onLocalCandidate = null
        onConnectionState = null
        onRemoteVideoTrack = null
        onAudioRouteChanged = null
        onAudioFocusRegained = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routeListener?.let { listener -> runCatching { audioManager.removeOnCommunicationDeviceChangedListener(listener) } }
            runCatching { audioManager.clearCommunicationDevice() }
        }
        routeListener = null
        routeListenerExecutor?.shutdown()
        routeListenerExecutor = null
        releaseAudioFocus()
        runCatching { capturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { connection.close() }
        runCatching { connection.dispose() }
        runCatching { factory.dispose() }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = previousAudioMode
    }

    private suspend fun create(start: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        start(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "createSdp failed")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        })
    }

    private suspend fun set(start: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        start(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "setSdp failed")) }
        })
    }
}
