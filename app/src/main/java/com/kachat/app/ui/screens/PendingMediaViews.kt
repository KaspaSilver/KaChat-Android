package com.kachat.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.ChatViewModel.NextcloudStagedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pending photo's thumbnail in a composer row; for a video "via Nextcloud", its first frame
 * with a play mark (iOS 7abc06e `VideoThumbnail`). Decoded off the main thread, display-only - the
 * real compression happens in the ViewModel when Send is tapped.
 */
@Composable
internal fun PendingMediaThumbnail(uri: Uri?, isVideo: Boolean) {
    val context = LocalContext.current
    val thumbnail by produceState<android.graphics.Bitmap?>(initialValue = null, uri, isVideo) {
        value = uri?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (isVideo) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, it)
                            retriever.getFrameAtTime(0)
                        } finally {
                            retriever.release()
                        }
                    } else {
                        context.contentResolver.openInputStream(it)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                        }
                    }
                }.getOrNull()
            }
        }
    }
    val bitmap = thumbnail ?: return
    Box(contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        if (isVideo) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp).shadow(2.dp, CircleShape))
        }
    }
}

/**
 * The pending media's label in a composer row (iOS 7abc06e `pendingPhotoRow`): "Photo", or while
 * it goes "via Nextcloud", its upload - a spinner and "Uploading to Nextcloud…", a cloud check and
 * "Photo"/"Video" once the link is back, or "Upload failed. Sends on chain." for a photo that will
 * go on chain instead.
 */
@Composable
internal fun RowScope.PendingMediaLabel(staged: NextcloudStagedMedia?) {
    val colors = LocalAppColors.current
    when (val phase = staged?.phase) {
        NextcloudStagedMedia.Phase.Uploading -> {
            CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(2.dp))
            Text(stringResource(R.string.nc_uploading), color = colors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        }
        is NextcloudStagedMedia.Phase.Ready -> {
            Icon(Icons.Default.CloudDone, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (staged.isVideo) R.string.video else R.string.photo),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
        }
        NextcloudStagedMedia.Phase.Failed -> {
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = colors.warning, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.nc_upload_failed_sends_on_chain), color = colors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        }
        null -> Text(stringResource(R.string.photo), color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** The pending media's send button: spinning, and not tappable, while its Nextcloud upload runs
 *  (iOS 7abc06e) - a second tap would otherwise start a duplicate send. */
@Composable
internal fun PendingMediaSendButton(staged: NextcloudStagedMedia?, size: Dp, onSend: () -> Unit) {
    val uploading = staged?.isUploading == true
    IconButton(
        onClick = onSend,
        enabled = !uploading,
        modifier = Modifier.size(size).background(KaspaTeal, CircleShape)
    ) {
        if (uploading) {
            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.send_photo),
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
