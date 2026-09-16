package com.kachat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
    val live = call ?: return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
            window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        // A call keeps the screen awake, as a phone does.
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        CallScreen(call = live, callService = callService)
    }
}

/** One view for voice and video - a video call just puts the remote picture behind everything
 *  and a local preview in the corner. */
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
    val connected = call.phase == CallService.Phase.Connected
    val statusText = when (val phase = call.phase) {
        CallService.Phase.RingingOut -> "Calling…"
        CallService.Phase.RingingIn -> if (call.video) "Incoming video call" else "Incoming voice call"
        CallService.Phase.Connecting -> call.statusDetail ?: "Connecting…"
        CallService.Phase.Connected -> call.statusDetail ?: call.connectedAtMs?.let { start ->
            val seconds = ((nowMs - start) / 1000).coerceAtLeast(0)
            String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        } ?: "Connected"
        is CallService.Phase.Ended -> when (phase.reason) {
            "declined" -> "Declined"
            "no_answer" -> "No answer"
            "missed" -> "Missed call"
            "busy" -> "Busy"
            "failed" -> lastError ?: "Call failed"
            else -> "Call ended"
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val remote = call.remoteVideoTrack
        if (call.video && remote != null && connected) {
            VideoRendererView(track = remote, mirrored = false, overlay = false, modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (call.video && connected) Color.Black.copy(alpha = 0.35f) else Color.Transparent)
                    .padding(vertical = 20.dp, horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContactAvatar(
                    imageUrl = call.contact.knsAvatarUrl,
                    fallbackText = call.contact.avatarFallbackText,
                    size = 96.dp,
                    fontSize = 32.sp,
                    deviceContactPhotoUri = call.contact.systemContactPhotoUri,
                    backupPhotoBase64 = call.contact.backupPhotoBase64,
                )
                Text(call.contact.displayName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusText, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (call.phase == CallService.Phase.RingingIn) {
                Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundCallButton(Icons.Default.CallEnd, tint = Color(0xFFFF3B30), size = 72.dp) { callService.declineIncoming() }
                        Text("Decline", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundCallButton(if (call.video) Icons.Default.Videocam else Icons.Default.Phone, tint = Color(0xFF34C759), size = 72.dp) {
                            val needed = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (call.video) add(Manifest.permission.CAMERA)
                            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                            if (needed.isEmpty()) callService.acceptIncoming() else acceptLauncher.launch(needed.toTypedArray())
                        }
                        Text("Accept", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (call.phase !is CallService.Phase.Ended) {
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            CallControl(if (call.isMuted) Icons.Default.MicOff else Icons.Default.Mic, if (call.isMuted) "Unmute" else "Mute", active = call.isMuted) { callService.toggleMute() }
                            CallControl(if (call.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.VolumeDown, "Speaker", active = call.isSpeakerOn) { callService.toggleSpeaker() }
                            if (call.video) {
                                CallControl(if (call.isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam, "Camera", active = call.isCameraOff) { callService.toggleCamera() }
                                CallControl(Icons.Default.Cameraswitch, "Flip", active = false) { callService.flipCamera() }
                            }
                        }
                    }
                    if (call.phase is CallService.Phase.Ended) {
                        RoundCallButton(Icons.Default.Close, tint = Color.White.copy(alpha = 0.2f), size = 64.dp) { callService.dismissEnded() }
                    } else {
                        RoundCallButton(Icons.Default.CallEnd, tint = Color(0xFFFF3B30), size = 72.dp) { callService.hangUp() }
                    }
                }
            }
        }
        val local = call.localVideoTrack
        if (call.video && local != null && !call.isCameraOff) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 16.dp, end = 16.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
            ) {
                VideoRendererView(track = local, mirrored = true, overlay = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CallControl(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundCallButton(icon, tint = if (active) Color.White else Color.White.copy(alpha = 0.2f), size = 56.dp, foreground = if (active) Color.Black else Color.White, onClick = onClick)
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
    }
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

/** A WebRTC video track on screen. [overlay] puts the surface above the other renderer, for the
 *  local preview in the corner. */
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
    // The sink follows the track: attached while both exist, detached on the way out.
    val current = renderer
    DisposableEffect(track, current) {
        if (current != null) track.addSink(current)
        onDispose { if (current != null) runCatching { track.removeSink(current) } }
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
        envelope is CallEnvelope.Invite && envelope.video -> Icons.Default.Videocam
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
