package com.kachat.app.services

import android.content.Context
import android.media.AudioManager
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
class WebRTCClient(private val context: Context, iceServers: List<NextcloudTalkClient.IceServer>, val wantsVideo: Boolean) {
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
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var usingFrontCamera = true
    private var capturing = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousAudioMode = audioManager.mode

    /** Fired on WebRTC's own threads; CallService hops to its scope. */
    var onLocalCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionState: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null

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

        if (wantsVideo) {
            val videoSource = factory.createVideoSource(false)
            val track = factory.createVideoTrack("kachat-video", videoSource)
            connection.addTrack(track, listOf("kachat"))
            localVideoTrack = track
            surfaceHelper = SurfaceTextureHelper.create("KaChatCapture", eglBase.eglBaseContext)
            capturer = createCapturer()?.also { it.initialize(surfaceHelper, context.applicationContext, videoSource.capturerObserver) }
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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

    /** The audio route: earpiece or speaker. */
    fun setSpeaker(speaker: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = speaker
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
