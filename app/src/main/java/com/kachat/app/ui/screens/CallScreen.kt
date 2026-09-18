package com.kachat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import com.kachat.app.models.avatarFallbackText
import com.kachat.app.models.displayName
import com.kachat.app.services.CallService
import com.kachat.app.services.WebRTCClient
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.CallCodec
import com.kachat.app.util.CallEnvelope
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.Locale

/**
 * The call screen sits over the whole app whenever [CallService.session] exists - ringing in,
 * ringing out, connecting, connected and the brief "Call ended" beat - whichever screen is
 * showing underneath; a call is not a page of the chat it started from. Dismissal only ever
 * comes from the service clearing the session: swiping or backing out of a live call is not a
 * way to hang up. Mirrors iOS's MainTabView.fullScreenCover + CallView.
 */
@Composable
fun CallOverlay(callService: CallService) {
    val call by callService.session.collectAsState()
    val minimized by callService.isMinimized.collectAsState()
    val floating by callService.isInPictureInPicture.collectAsState()
    val live = call ?: return
    if (floating) {
        // In the system's small window there is room for one thing: the other person. No
        // controls - the window's own tap brings the app back.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            live.remoteVideoTrack?.let { track ->
                VideoRendererView(track = track, mirrored = false, overlay = false, modifier = Modifier.fillMaxSize())
            }
        }
        return
    }
    if (minimized) {
        // Tucked away: the call goes on while the rest of the app is used. A bar across the top
        // says so and taps back into it, the way a phone shows a call you have left.
        CallReturnBar(call = live, onReturn = { callService.restore() })
        return
    }
    // Drawn straight into the activity's window, not a Dialog: a Compose Dialog sizes its
    // window from content measured against the nominal screen height, so the bottom of a
    // full-screen layout - the control bar here - lands under the navigation bar on many
    // phones (the same trap the KaPosts overlays hit). The activity is edge-to-edge and knows
    // the real insets on every device, so `safeDrawing` padding below is right everywhere.
    // Back does nothing while a call is up, and the sheet swallows touches so the app
    // beneath cannot be poked through it.
    BackHandler(enabled = true) {}
    val view = LocalView.current
    // A call keeps the screen awake, as a phone does.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        CallScreen(call = live, callService = callService)
        // Tucks the call away so the rest of the app can be used while it goes on. Not offered
        // on a call that is still ringing or already over - there is nothing to go back to.
        if (live.phase == CallService.Phase.Connecting || live.phase == CallService.Phase.Connected) {
            IconButton(
                onClick = { callService.minimize() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Tuck the call away",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

/**
 * Voice calls look like the phone's own call screen: name and timer up top, big round buttons
 * with labels underneath, the red hang-up at the bottom. Video calls are two equal tiles, the
 * other person on top and you underneath, both shown exactly as the camera sees them (no
 * mirroring - the picture the other side gets is the picture you see), with a slim control
 * bar below. Mirrors iOS's CallView.
 */
@Composable
private fun CallScreen(call: CallService.ActiveCall, callService: CallService) {
    val context = LocalContext.current
    val lastError by callService.lastError.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallService.Phase.Connected) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Accepting needs the microphone (and the camera for video) - asked right here, on the
    // Accept tap, so the permission dialog reads as part of picking up.
    val acceptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            callService.acceptIncoming()
        } else {
            Toast.makeText(context, "KaChat needs the microphone to take a call.", Toast.LENGTH_SHORT).show()
            callService.declineIncoming()
        }
    }
    val accept: () -> Unit = {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (call.video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) callService.acceptIncoming() else acceptLauncher.launch(needed.toTypedArray())
    }
    val timer = call.connectedAtMs?.let { start ->
        val seconds = ((nowMs - start) / 1000).coerceAtLeast(0)
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
    val statusText = when (val phase = call.phase) {
        CallService.Phase.RingingOut -> "calling…"
        CallService.Phase.RingingIn -> if (call.video) "KaChat Video" else "KaChat Audio"
        CallService.Phase.Connecting -> call.statusDetail ?: "connecting…"
        CallService.Phase.Connected -> call.statusDetail ?: timer ?: "connected"
        is CallService.Phase.Ended -> when (phase.reason) {
            "declined" -> "Declined"
            // A request nobody hosted rings out exactly like an unanswered call - the other
            // side never says anything on chain - so the hint rides along here.
            "no_answer" -> if (call.hostsThisCall) "No answer" else "No answer. If they don't have Nextcloud Talk, one of you needs it to make calls."
            "missed" -> "Missed call"
            "busy" -> "Busy"
            "no_host" -> "One person in this chat needs Nextcloud Talk set up to make calls."
            "failed" -> lastError ?: "Call failed"
            else -> "Call ended"
        }
    }
    // A video call shows its two tiles from the first second, ringing out included: the contact
    // up top with "calling...", your own camera below. Matches iOS.
    val isVideoLayout = call.video && (
        call.phase == CallService.Phase.Connected ||
            call.phase == CallService.Phase.Connecting ||
            (call.phase == CallService.Phase.RingingOut && call.isOutgoing)
        )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideoLayout) {
            VideoLayout(call, callService, timer)
        } else {
            VoiceLayout(call, callService, statusText, onAccept = accept)
        }
    }
}

// ---- Voice (and every ringing/ended state) ----

@Composable
private fun VoiceLayout(call: CallService.ActiveCall, callService: CallService, statusText: String, onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.padding(top = 56.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ContactAvatar(
                imageUrl = call.contact.knsAvatarUrl,
                fallbackText = call.contact.avatarFallbackText,
                size = 120.dp,
                fontSize = 40.sp,
                deviceContactPhotoUri = call.contact.systemContactPhotoUri,
                backupPhotoBase64 = call.contact.backupPhotoBase64,
            )
            Text(call.contact.displayName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusText, color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.weight(1f))
        when (val phase = call.phase) {
            CallService.Phase.RingingIn -> {
                Row(horizontalArrangement = Arrangement.spacedBy(96.dp)) {
                    BigButton(Icons.Default.CallEnd, tint = Color(0xFFFF3B30), label = "Decline") { callService.declineIncoming() }
                    BigButton(if (call.video) Icons.Default.Videocam else Icons.Default.Phone, tint = Color(0xFF34C759), label = "Accept", onClick = onAccept)
                }
            }
            is CallService.Phase.Ended -> {
                BigButton(Icons.Default.Close, tint = Color.White.copy(alpha = 0.22f), label = "Close") { callService.dismissEnded() }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                        // Off: translucent circle, plain glyph. On: solid white circle, the
                        // crossed-out mic / loud speaker glyph, and the label says so - one look
                        // tells you whether you are muted and where the sound is going.
                        BigButton(
                            if (call.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            tint = if (call.isMuted) Color.White else Color.White.copy(alpha = 0.22f),
                            label = if (call.isMuted) "muted" else "mute",
                            foreground = if (call.isMuted) Color.Black else Color.White,
                        ) { callService.toggleMute() }
                        BigButton(
                            if (call.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                            tint = if (call.isSpeakerOn) Color.White else Color.White.copy(alpha = 0.22f),
                            label = if (call.isSpeakerOn) "speaker on" else "speaker",
                            foreground = if (call.isSpeakerOn) Color.Black else Color.White,
                        ) { callService.toggleSpeaker() }
                    }
                    if (!call.video && call.phase == CallService.Phase.Connected) {
                        // Turns this into a video call for both sides - nobody hangs up.
                        BigButton(
                            Icons.Default.Videocam,
                            tint = Color.White.copy(alpha = 0.22f),
                            label = "video",
                        ) { callService.upgradeToVideo() }
                    }
                    BigButton(Icons.Default.CallEnd, tint = Color(0xFFFF3B30), label = null) { callService.hangUp() }
                }
            }
        }
    }
}

// ---- Video ----

@Composable
private fun VideoLayout(call: CallService.ActiveCall, callService: CallService, timer: String?) {
    // The call takes the camera back by turning this off, and waits for the tile below to let go.
    val standInWanted by callService.ringOutCamera.active.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 8.dp)
            .padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VideoTile(
            track = call.remoteVideoTrack,
            name = call.contact.displayName,
            timer = if (call.phase == CallService.Phase.Connected) timer else null,
            placeholder = when (call.phase) {
                CallService.Phase.Connected -> "Camera off"
                CallService.Phase.RingingOut -> "calling…"
                else -> call.statusDetail ?: "Connecting…"
            },
            contact = call.contact,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        VideoTile(
            track = if (call.isCameraOff) null else call.localVideoTrack,
            name = "You",
            timer = null,
            placeholder = "Camera off",
            contact = null,
            // Before the call has a camera of its own, the stand-in fills this tile.
            standInCamera = standInWanted && !call.isCameraOff && call.localVideoTrack == null,
            onCameraHeld = { callService.ringOutCamera.noteHeld() },
            onCameraReleased = { callService.ringOutCamera.noteReleased() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(88.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No speaker control here: a video call is never held to an ear, so it stays on
            // the speaker and mute is the control that matters.
            SmallControl(if (call.isMuted) Icons.Default.MicOff else Icons.Default.Mic, active = call.isMuted) { callService.toggleMute() }
            SmallControl(if (call.isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam, active = call.isCameraOff) { callService.toggleCamera() }
            SmallControl(Icons.Default.Cameraswitch, active = false) { callService.flipCamera() }
            RoundCallButton(Icons.Default.CallEnd, tint = Color(0xFFFF3B30), size = 60.dp) { callService.hangUp() }
        }
    }
}

/** One tile: the picture as the camera sees it, or an avatar / glyph with a placeholder line
 *  when there is none, and a name chip (with the timer on the other person's). */
@Composable
private fun VideoTile(
    track: VideoTrack?,
    name: String,
    timer: String?,
    placeholder: String,
    contact: com.kachat.app.models.ContactEntity?,
    modifier: Modifier,
    standInCamera: Boolean = false,
    onCameraHeld: () -> Unit = {},
    onCameraReleased: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1F1F1F)),
        contentAlignment = Alignment.Center,
    ) {
        if (track != null) {
            VideoRendererView(track = track, mirrored = false, overlay = contact == null, modifier = Modifier.fillMaxSize())
        } else if (standInCamera) {
            RingOutCameraPreview(onHeld = onCameraHeld, onReleased = onCameraReleased, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (contact != null) {
                    ContactAvatar(
                        imageUrl = contact.knsAvatarUrl,
                        fallbackText = contact.avatarFallbackText,
                        size = 72.dp,
                        fontSize = 26.sp,
                        deviceContactPhotoUri = contact.systemContactPhotoUri,
                        backupPhotoBase64 = contact.backupPhotoBase64,
                    )
                } else {
                    Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(34.dp))
                }
                Text(placeholder, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (timer != null) Text(timer, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp)
        }
    }
}

/**
 * Your own camera while a video call rings out, before the call has one of its own. Let go the
 * moment this leaves the screen, and the call is told so - only one thing may hold the camera,
 * and the call itself is about to want it.
 */
@Composable
private fun RingOutCameraPreview(onHeld: () -> Unit, onReleased: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    DisposableEffect(Unit) {
        onHeld()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                cameraProvider.unbindAll()
                // The front camera, the same one the call starts on.
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { provider?.unbindAll() }
            onReleased()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * The green bar across the top of the app while a call is tucked away: who it is with, how long
 * it has been going, and a tap to come back to it.
 */
@Composable
private fun CallReturnBar(call: CallService.ActiveCall, onReturn: () -> Unit) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallService.Phase.Connected) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val timer = call.connectedAtMs?.let { start ->
        val seconds = ((nowMs - start) / 1000).coerceAtLeast(0)
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF34C759))
            .clickable { onReturn() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (call.video) Icons.Default.Videocam else Icons.Default.Phone,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "${call.contact.displayName}${timer?.let { " · $it" } ?: ""} · Tap to return",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- Pieces ----

@Composable
private fun BigButton(icon: ImageVector, tint: Color, label: String?, foreground: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundCallButton(icon, tint = tint, size = 84.dp, foreground = foreground, onClick = onClick)
        if (label != null) Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
    }
}

@Composable
private fun SmallControl(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    RoundCallButton(icon, tint = if (active) Color.White else Color.White.copy(alpha = 0.22f), size = 52.dp, foreground = if (active) Color.Black else Color.White, onClick = onClick)
}

@Composable
private fun RoundCallButton(icon: ImageVector, tint: Color, size: Dp, foreground: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(size * 0.42f))
    }
}

/** A WebRTC video track on screen, as the camera sees it - never mirrored. [overlay] puts the
 *  surface above the other renderer (two SurfaceViews in one window need an order). */
@Composable
private fun VideoRendererView(track: VideoTrack, mirrored: Boolean, overlay: Boolean, modifier: Modifier = Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(WebRTCClient.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirrored)
                if (overlay) setZOrderMediaOverlay(true)
                renderer = this
            }
        },
        update = { view -> view.setMirror(mirrored) },
        onRelease = { view -> runCatching { view.release() } },
    )
    // The sink follows the track: attached while both exist, detached on the way out. The
    // service publishes "tracks gone" before it destroys the peer connection (see
    // CallService.finish), so this detach runs against a live track; a detach that still
    // finds the track disposed is skipped rather than allowed to hang the main thread.
    val current = renderer
    DisposableEffect(track, current) {
        if (current != null) runCatching { track.addSink(current) }
        onDispose {
            if (current != null) runCatching {
                // `id()` throws IllegalStateException on a disposed track - the cheap probe.
                track.id()
                track.removeSink(current)
            }
        }
    }
}

/**
 * One line per call event in the chat, like a phone's recents - what it was, and for a
 * finished call how long it lasted. The invite/response/end trio all land as separate
 * messages, so each says its own piece. Mirrors iOS's MessageBubbleView.callBubble.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallBubble(envelope: CallEnvelope, isSent: Boolean, onLongPress: () -> Unit = {}) {
    val (text, ended) = CallCodec.bubbleText(envelope, isOutgoing = isSent)
    val icon = when {
        (envelope is CallEnvelope.Invite && envelope.video) || (envelope is CallEnvelope.Request && envelope.video) -> Icons.Default.Videocam
        ended -> Icons.Default.PhoneDisabled
        else -> Icons.Default.Phone
    }
    Surface(
        color = if (isSent) KaspaTeal else LocalAppColors.current.surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val color = if (isSent) Color.Black else LocalAppColors.current.textPrimary
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}
