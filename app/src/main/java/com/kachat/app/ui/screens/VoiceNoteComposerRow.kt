package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosGlass
import com.kachat.app.viewmodels.ChatViewModel

/**
 * The composer's voice row, the same in 1:1 and group chats (iOS ca013fc8): while recording, a
 * ring filling toward the limit and "Recording... 00:12 / 02:50", and the button stops it; then
 * "Encoding…"; then "Audio ready • 00:12" with a play / pause preview, and the button sends. The
 * trash throws the note away at any stage.
 */
@Composable
fun VoiceNoteComposerRow(
    state: ChatViewModel.VoiceRecordingState,
    onTrash: () -> Unit,
    onStop: () -> Unit,
    onTogglePreview: () -> Unit,
    onSend: () -> Unit,
) {
    val status = state.status
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            // iOS pendingPhotoRow / the audio row: glassBackground(cornerRadius: 20)
            .iosGlass(cornerRadius = 20.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onTrash) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_recording), tint = LocalAppColors.current.danger)
        }
        when (status) {
            ChatViewModel.VoiceRecordingStatus.RECORDING -> {
                // A ring that fills toward the limit (2:50 / 1:50 on chain, 5:00 via Nextcloud).
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        color = LocalAppColors.current.textSecondary.copy(alpha = 0.25f),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    CircularProgressIndicator(
                        progress = { (state.elapsedMs.toFloat() / maxOf(1L, state.maxMs)).coerceIn(0f, 1f) },
                        color = LocalAppColors.current.danger,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.recording, "${voiceNoteClock(state.elapsedMs)} / ${voiceNoteClock(state.maxMs)}"),
                    color = LocalAppColors.current.textPrimary,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier.weight(1f)
                )
            }
            ChatViewModel.VoiceRecordingStatus.ENCODING -> {
                CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.encoding_ellipsis),
                    color = LocalAppColors.current.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
            else -> {
                IconButton(onClick = onTogglePreview, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (state.previewPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = if (state.previewPlaying) "Pause" else "Play",
                        tint = KaspaTeal,
                        modifier = Modifier.size(28.dp)
                    )
                }
                // "00:12" for a ready note, "00:04/00:12" while its preview plays (iOS).
                val label = if (state.previewPlaying) {
                    "${voiceNoteClock(state.previewPositionMs)}/${voiceNoteClock(state.previewDurationMs)}"
                } else {
                    voiceNoteClock(state.previewDurationMs)
                }
                Text(
                    text = "${stringResource(R.string.audio_ready)} • $label",
                    color = LocalAppColors.current.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // The button stops the recording (so does the length limit), and sends a ready note.
        val ready = status == ChatViewModel.VoiceRecordingStatus.READY
        val recording = status == ChatViewModel.VoiceRecordingStatus.RECORDING
        IconButton(
            onClick = { if (recording) onStop() else if (ready) onSend() },
            enabled = recording || ready,
            modifier = Modifier
                .size(40.dp)
                .background(if (recording || ready) KaspaTeal else LocalAppColors.current.textSecondary.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(
                imageVector = if (recording) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (recording) "Stop" else stringResource(R.string.send),
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** "mm:ss", as the composer shows it (iOS `VoiceNoteRecorder.clock`). */
fun voiceNoteClock(ms: Long): String {
    val total = Math.round(maxOf(0L, ms) / 1000.0).toInt()
    return String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60, total % 60)
}
