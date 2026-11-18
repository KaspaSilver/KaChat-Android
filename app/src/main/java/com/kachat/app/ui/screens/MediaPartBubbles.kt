package com.kachat.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.VoiceNoteCodec
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.InlineMediaSniff
import com.kachat.app.util.VoiceMessage
import com.kachat.app.util.VoiceMessageContent
import com.kachat.app.util.VoicePartCluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The one bubble of a long voice note or multi-part photo (iOS 7c5c22e3 `LazyMultipartAudioBubble`,
 * `PhotoPartsPlaceholderBubble`; MESSAGING.md "Long voice notes (multi-part)" and "Photos:
 * quality tiers and multi-part photos"). The row stands for the note's earliest part; its parts
 * present come in as [MediaPartsInput].
 */
class MediaPartsInput(
    /** How many parts the note has in all. */
    val count: Int,
    /** The parts present, in index order: (tag index, media-file JSON). */
    val parts: List<Pair<Int, String>>,
) {
    val isComplete: Boolean get() = parts.size >= count && parts.map { it.first } == (1..count).toList()
    val isPhoto: Boolean get() = parts.firstOrNull()?.second?.let { InlineMediaSniff.mimeType(it)?.lowercase()?.startsWith("image/") } == true
    /** Changes whenever a part arrives. */
    val signature: String get() = parts.joinToString(",") { "${it.first}:${it.second.length}" }

    /** Each present part's file bytes, in order (a part that doesn't parse is left out). */
    fun partBytes(): List<ByteArray> = parts.mapNotNull { (_, content) ->
        val media = (if (isPhoto) ImageMessage.parseOrNull(content) else VoiceMessage.parseOrNull(content)) ?: return@mapNotNull null
        runCatching { java.util.Base64.getDecoder().decode(VoiceMessage.base64Payload(media)) }.getOrNull()
    }

    companion object {
        /** The parts of [cluster] whose text [content] gives. */
        fun <M> of(cluster: VoicePartCluster<M>, content: (M) -> String?): MediaPartsInput =
            MediaPartsInput(
                count = cluster.count,
                parts = cluster.parts.zip(cluster.partIndexes).mapNotNull { (message, index) -> content(message)?.let { index to it } },
            )
    }
}

/** A multi-part photo's chunks joined in index order - only once every chunk is here - as the
 *  media-file JSON one photo would have been; null until then (iOS `PhotoPartsInput.joinedMedia`). */
fun joinedPhotoContent(input: MediaPartsInput): VoiceMessageContent? {
    if (!input.isPhoto || !input.isComplete) return null
    val chunks = input.partBytes()
    if (chunks.size != input.count) return null
    val joined = java.io.ByteArrayOutputStream(chunks.sumOf { it.size })
    for (chunk in chunks) joined.write(chunk)
    val bytes = joined.toByteArray()
    val mime = InlineMediaSniff.mimeType(input.parts.first().second) ?: "image/jpeg"
    return VoiceMessageContent(
        name = "photo.jpg",
        size = bytes.size.toLong(),
        mimeType = mime,
        content = "data:$mime;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}",
    )
}

/** A multi-part photo still arriving: a placeholder with "Receiving… N of M". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoPartsPlaceholderBubble(input: MediaPartsInput, isSent: Boolean, onLongPress: () -> Unit, onDoubleClick: () -> Unit = {}) {
    val foreground = if (isSent) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textSecondary
    Surface(
        color = if (isSent) LocalAppColors.current.outgoingBubble else LocalAppColors.current.incomingBubble,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .size(width = 220.dp, height = 160.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Photo, contentDescription = null, tint = foreground, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(color = foreground, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.receiving_n_of_m, input.parts.size, input.count), color = foreground, fontSize = 12.sp)
        }
    }
}

/**
 * The one bubble of a long voice note: total length, one scrubber across all parts, and while
 * parts are still on their way, "Receiving… 7 of 12". Every part present is decoded and joined
 * with no gap ([VoiceNoteCodec.decodePartsToWav]), so the length is the total and seeking crosses
 * parts; a part that arrives meanwhile is picked up on the next Play.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultipartAudioBubble(input: MediaPartsInput, isSent: Boolean, onLongPress: () -> Unit, onDoubleClick: () -> Unit = {}) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playerFile by remember { mutableStateOf<java.io.File?>(null) }
    var loadedSignature by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0L) }
    var positionMs by remember { mutableStateOf(0L) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    var playRequested by remember { mutableStateOf(false) }

    // The length, kept without the audio (iOS MessageBubbleView `preload`): worked out again
    // whenever a part arrives (unless playing: the next Play picks it up). Nothing is decoded to a
    // file or held in a player until Play - a chat with several long notes held a MediaPlayer
    // and a WAV of up to 16 MB for every one on screen.
    LaunchedEffect(input.signature) {
        if (isPlaying) return@LaunchedEffect
        isLoading = true
        val total = withContext(Dispatchers.Default) { partsDurationMs(context.cacheDir, input) }
        if (total != null && total > 0L) durationMs = total
        isLoading = false
    }

    // Play: the parts as one WAV and a player for it, made now (iOS `togglePlayback`), and again
    // when parts arrived since.
    LaunchedEffect(playRequested) {
        if (!playRequested) return@LaunchedEffect
        val signature = input.signature
        player?.takeIf { loadedSignature == signature }?.let {
            playRequested = false
            it.start()
            isPlaying = true
            return@LaunchedEffect
        }
        isLoading = true
        val output = java.io.File(context.cacheDir, "voice_parts_${System.nanoTime()}.wav")
        val decoded = try {
            withContext(Dispatchers.Default) {
                val parts = input.partBytes()
                if (parts.isEmpty()) null else VoiceNoteCodec.decodePartsToWav(parts, output)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // scrolled away or a part arrived mid-decode: the finished file is no one's
            output.delete()
            throw e
        }
        player?.let { runCatching { it.release() } }
        playerFile?.delete()
        player = null
        playerFile = null
        loadedSignature = null
        if (decoded == null) {
            playRequested = false
            isLoading = false
            return@LaunchedEffect
        }
        val mediaPlayer = android.media.MediaPlayer()
        try {
            mediaPlayer.setDataSource(decoded.file.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
                seekFraction = 0f
                runCatching { it.seekTo(0) }
            }
            player = mediaPlayer
            playerFile = decoded.file
            loadedSignature = signature
            durationMs = mediaPlayer.duration.toLong().takeIf { it > 0 } ?: decoded.durationMs
            if (seekFraction > 0f) mediaPlayer.seekTo((seekFraction * durationMs).toInt())
            mediaPlayer.start()
            isPlaying = true
        } catch (e: Exception) {
            android.util.Log.e("MultipartAudioBubble", "Could not prepare the voice note for playback", e)
            runCatching { mediaPlayer.release() }
            decoded.file.delete()
        }
        playRequested = false
        isLoading = false
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            val current = player ?: break
            positionMs = runCatching { current.currentPosition.toLong() }.getOrDefault(positionMs)
            if (durationMs > 0) seekFraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            delay(100)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.let { runCatching { if (it.isPlaying) it.stop() }; runCatching { it.release() } }
            playerFile?.delete()
        }
    }

    val foreground = if (isSent) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textPrimary
    Surface(
        color = if (isSent) LocalAppColors.current.outgoingBubble else LocalAppColors.current.incomingBubble,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .widthIn(min = 200.dp, max = 260.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading && !playRequested && player == null) {
                CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else {
                IconButton(
                    onClick = {
                        val current = player
                        if (isPlaying) {
                            current?.pause()
                            isPlaying = false
                        } else if (current != null && loadedSignature == input.signature) {
                            current.start()
                            isPlaying = true
                        } else {
                            playRequested = true
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = KaspaTeal
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                // One scrubber across every part.
                Slider(
                    value = seekFraction,
                    onValueChange = { fraction ->
                        seekFraction = fraction
                        player?.let { if (durationMs > 0) it.seekTo((fraction * durationMs).toInt()) }
                        positionMs = (fraction * durationMs).toLong()
                    },
                    colors = SliderDefaults.colors(thumbColor = KaspaTeal, activeTrackColor = KaspaTeal),
                    modifier = Modifier.height(24.dp)
                )
                Text(
                    text = if (durationMs > 0) VoiceMessage.formatDuration((if (isPlaying || positionMs > 0) positionMs else durationMs).toInt()) else "...",
                    color = foreground,
                    fontSize = 13.sp
                )
                if (!input.isComplete) {
                    Text(
                        stringResource(R.string.receiving_n_of_m, input.parts.size, input.count),
                        color = if (isSent) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/** The voice note's length from its parts' own Duration, without decoding them; a part that
 *  doesn't carry one is decoded once and the file dropped at once (iOS `preload`). Null when no
 *  part reads. */
private fun partsDurationMs(cacheDir: java.io.File, input: MediaPartsInput): Long? {
    val parts = input.partBytes()
    if (parts.isEmpty()) return null
    var totalMs = 0.0
    for (part in parts.take(com.kachat.app.util.VoicePartTag.MAX_PARTS)) {
        val parsed = com.kachat.app.util.WebMOpusReader.parse(part) ?: continue
        val ms = parsed.durationMs
        if (ms == null) {
            val scratch = java.io.File(cacheDir, "voice_parts_len_${System.nanoTime()}.wav")
            return try {
                VoiceNoteCodec.decodePartsToWav(parts, scratch)?.durationMs
            } finally {
                scratch.delete()
            }
        }
        // The header comes from the sender: no genuine part runs past the cap (iOS 83806b3d).
        totalMs += minOf(ms, (com.kachat.app.util.VoicePartTag.MAX_PART_SECONDS + 1) * 1000.0)
    }
    return if (totalMs > 0.0) Math.round(totalMs) else null
}
