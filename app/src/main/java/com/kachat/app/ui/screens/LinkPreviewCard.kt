package com.kachat.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.models.KaspaExplorer
import com.kachat.app.services.LinkPreviewData
import com.kachat.app.services.LinkPreviewService
import com.kachat.app.ui.theme.LocalAppColors

private val VIDEO_HOSTS = setOf("youtube.com", "www.youtube.com", "youtu.be", "m.youtube.com")

/** Rich link-preview card shown below a chat bubble's text when the message contains a link -
 *  mirrors iMessage. Renders nothing while the fetch is in flight, rather than a placeholder that
 *  could flash or look broken. If no preview data is found (a bare/broken link, or a site with no
 *  Open Graph tags), falls back to [fallbackText] if given, else renders nothing. Used by
 *  [MessageBubble] and `GroupMessageBubble` only - broadcast rooms never call this.
 *
 *  [txId] is the owning message's transaction id, for the "View in Explorer" long-press action -
 *  matches every other bubble type's identical action ([MessageBubble]'s
 *  `kaspaExplorer.txUrl(message.id)` call site). */
@Composable
fun LinkPreviewCard(
    url: String,
    txId: String,
    kaspaExplorer: KaspaExplorer = KaspaExplorer.default,
    /** Non-null only when this card is standing in for the *entire* message (nothing but a bare
     *  link, no separate text bubble shown alongside it) - shown as a plain tappable-link bubble
     *  if the fetch finds no preview data, so the message doesn't render as nothing at all. Null
     *  when used alongside a real text bubble, where showing nothing on failure is correct since
     *  the message's own text is already visible. Mirrors iOS's `LinkPreviewCardView.fallbackText`. */
    fallbackText: String? = null,
    /** Enters the chat's message multi-select mode with this message pre-selected - null disables
     *  the "Select" long-press menu option entirely. Mirrors [MessageBubble]'s onSelect. */
    onSelect: (() -> Unit)? = null,
    /** Double-tapping the preview opens the owning message's quick-reaction menu (reactions +
     *  reply), exactly like double-tapping a normal message bubble. Null disables it. Single tap
     *  still opens the link. Mirrors iOS's `LinkPreviewCardView.onDoubleTap`. */
    onDoubleTap: (() -> Unit)? = null
) {
    var preview by remember(url) { mutableStateOf<LinkPreviewData?>(null) }
    var hasFinishedLoading by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        hasFinishedLoading = false
        preview = LinkPreviewService.fetchPreview(url)
        hasFinishedLoading = true
    }

    if (hasFinishedLoading && preview != null) {
        val data = preview!!
        if (data.nextcloudMedia == "image" || data.nextcloudMedia == "video") {
            // Nextcloud media renders as a bare photo/video bubble (like a sent photo), not a
            // titled link card — the media IS the message. Mirrors iOS's nextcloudMediaBubble.
            NextcloudMediaBubble(data = data, url = url, txId = txId, kaspaExplorer = kaspaExplorer, onSelect = onSelect, onDoubleTap = onDoubleTap)
        } else if (data.nextcloudMedia != null) {
            // Audio/PDF/generic files get an attachment card: icon + filename + kind/size caption.
            NextcloudAttachmentCard(data = data, url = url, txId = txId, kaspaExplorer = kaspaExplorer, onSelect = onSelect, onDoubleTap = onDoubleTap)
        } else {
            LinkPreviewCardContent(data = data, url = url, txId = txId, kaspaExplorer = kaspaExplorer, onSelect = onSelect, onDoubleTap = onDoubleTap)
        }
    } else if (hasFinishedLoading && fallbackText != null) {
        LinkPreviewFallbackBubble(text = fallbackText, url = url, txId = txId, kaspaExplorer = kaspaExplorer, onSelect = onSelect, onDoubleTap = onDoubleTap)
    }
}

@Composable
private fun LinkPreviewFallbackBubble(text: String, url: String, txId: String, kaspaExplorer: KaspaExplorer, onSelect: (() -> Unit)? = null, onDoubleTap: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

    Text(
        text,
        color = LocalAppColors.current.textPrimary,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
            .pointerInput(url) {
                detectTapGestures(
                    onLongPress = { showMenu = true },
                    onDoubleTap = { onDoubleTap?.invoke() },
                    onTap = { uriHandler.openUri(url) }
                )
            }
    )

    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.ContentCopy, "Copy Link") {
                clipboardManager.setText(AnnotatedString(url))
                showMenu = false
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.Public, "View in Explorer") {
                uriHandler.openUri(kaspaExplorer.txUrl(txId))
                showMenu = false
            }
            if (onSelect != null) {
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.CheckCircle, "Select") {
                    onSelect()
                    showMenu = false
                }
            }
        }
    }
}

/**
 * Bare media bubble for a Nextcloud public-share link — just the poster image at its real aspect
 * ratio (no title/description/site chrome), centered play badge for videos. Tap: photos open the
 * in-app full-screen viewer; videos hand the `/download` URL (streams via range requests) to the
 * system player via ACTION_VIEW — the app deliberately has no bundled media player dependency.
 * Long-press menu matches every other link preview's.
 */
@Composable
private fun NextcloudMediaBubble(data: LinkPreviewData, url: String, txId: String, kaspaExplorer: KaspaExplorer, onSelect: (() -> Unit)? = null, onDoubleTap: (() -> Unit)? = null) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val isVideo = data.nextcloudMedia == "video"

    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    var showPhotoViewer by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
            .pointerInput(url) {
                detectTapGestures(
                    onLongPress = { showMenu = true },
                    onDoubleTap = { onDoubleTap?.invoke() },
                    onTap = {
                        val download = data.mediaDownloadUrl
                        when {
                            download == null -> uriHandler.openUri(url)
                            isVideo -> {
                                // No media3/ExoPlayer in this app — the system player streams it.
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(android.net.Uri.parse(download), "video/*")
                                    })
                                } catch (e: Exception) {
                                    uriHandler.openUri(url)
                                }
                            }
                            else -> showPhotoViewer = true
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = data.imageUrl,
            contentDescription = data.title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 320.dp),
            loading = {
                Box(
                    modifier = Modifier
                        .size(width = 240.dp, height = 180.dp)
                        .background(LocalAppColors.current.surface),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = LocalAppColors.current.textSecondary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            },
            error = {
                Box(
                    modifier = Modifier
                        .size(width = 240.dp, height = 180.dp)
                        .background(LocalAppColors.current.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isVideo) Icons.Default.Movie else Icons.Default.Photo,
                        contentDescription = null,
                        tint = LocalAppColors.current.textSecondary
                    )
                }
            }
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
            }
        }
    }

    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.ContentCopy, "Copy Link") {
                clipboardManager.setText(AnnotatedString(url))
                showMenu = false
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.Public, "View in Explorer") {
                uriHandler.openUri(kaspaExplorer.txUrl(txId))
                showMenu = false
            }
            if (onSelect != null) {
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.CheckCircle, "Select") {
                    onSelect()
                    showMenu = false
                }
            }
        }
    }

    if (showPhotoViewer && data.mediaDownloadUrl != null) {
        NextcloudPhotoViewerDialog(
            downloadUrl = data.mediaDownloadUrl,
            shareUrl = url,
            onDismiss = { showPhotoViewer = false }
        )
    }
}

/**
 * Attachment card for non-visual Nextcloud shares (audio/pdf/generic file): leading kind icon,
 * filename, and a "KIND · SIZE" caption. Tap: audio streams via the system player (ACTION_VIEW,
 * same approach as video); PDFs open the in-app PdfRenderer viewer; everything else opens the
 * SHARE url in the browser — Nextcloud's web viewer is the only renderer for Office docs.
 */
@Composable
private fun NextcloudAttachmentCard(data: LinkPreviewData, url: String, txId: String, kaspaExplorer: KaspaExplorer, onSelect: (() -> Unit)? = null, onDoubleTap: (() -> Unit)? = null) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val kind = data.nextcloudMedia ?: "file"

    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    var showPdfViewer by remember { mutableStateOf(false) }

    val icon = when (kind) {
        "audio" -> Icons.Default.GraphicEq
        "pdf" -> Icons.Default.PictureAsPdf
        else -> Icons.Default.Description
    }
    val kindLabel = when (kind) {
        "audio" -> "AUDIO"
        "pdf" -> "PDF"
        else -> data.title?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.uppercase() ?: "FILE"
    }
    val sizeText = data.mediaByteSize?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
    val caption = listOfNotNull(kindLabel, sizeText).joinToString(" · ")

    Row(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LocalAppColors.current.surface)
            .onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
            .pointerInput(url) {
                detectTapGestures(
                    onLongPress = { showMenu = true },
                    onDoubleTap = { onDoubleTap?.invoke() },
                    onTap = {
                        val download = data.mediaDownloadUrl
                        when {
                            kind == "audio" && download != null -> {
                                // Same system-player streaming approach as video.
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(android.net.Uri.parse(download), "audio/*")
                                    })
                                } catch (e: Exception) {
                                    uriHandler.openUri(url)
                                }
                            }
                            kind == "pdf" && download != null -> showPdfViewer = true
                            else -> uriHandler.openUri(url)
                        }
                    }
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                data.title ?: "File",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                caption,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.ContentCopy, "Copy Link") {
                clipboardManager.setText(AnnotatedString(url))
                showMenu = false
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.Public, "View in Explorer") {
                uriHandler.openUri(kaspaExplorer.txUrl(txId))
                showMenu = false
            }
            if (onSelect != null) {
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.CheckCircle, "Select") {
                    onSelect()
                    showMenu = false
                }
            }
        }
    }

    if (showPdfViewer && data.mediaDownloadUrl != null) {
        NextcloudPdfViewerDialog(
            downloadUrl = data.mediaDownloadUrl,
            shareUrl = url,
            onDismiss = { showPdfViewer = false }
        )
    }
}

@Composable
private fun LinkPreviewCardContent(data: LinkPreviewData, url: String, txId: String, kaspaExplorer: KaspaExplorer, onSelect: (() -> Unit)? = null, onDoubleTap: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val isVideoLink = remember(url) {
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() in VIDEO_HOSTS
    }

    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LocalAppColors.current.surface)
            .onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
            .pointerInput(url) {
                detectTapGestures(
                    onLongPress = { showMenu = true },
                    onDoubleTap = { onDoubleTap?.invoke() },
                    onTap = { uriHandler.openUri(url) }
                )
            }
    ) {
        if (data.imageUrl != null) {
            Box {
                SubcomposeAsyncImage(
                    model = data.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
                if (isVideoLink) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(10.dp)) {
            if (!data.title.isNullOrEmpty()) {
                Text(
                    data.title,
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!data.description.isNullOrEmpty()) {
                Text(
                    data.description,
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!data.siteName.isNullOrEmpty()) {
                Text(
                    data.siteName.uppercase(),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.ContentCopy, "Copy Link") {
                clipboardManager.setText(AnnotatedString(url))
                showMenu = false
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.Public, "View in Explorer") {
                uriHandler.openUri(kaspaExplorer.txUrl(txId))
                showMenu = false
            }
            if (onSelect != null) {
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.CheckCircle, "Select") {
                    onSelect()
                    showMenu = false
                }
            }
        }
    }
}
