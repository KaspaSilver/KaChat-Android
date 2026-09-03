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
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.kachat.app.models.KaspaExplorer
import com.kachat.app.services.LinkPreviewData
import com.kachat.app.services.KaPostLinkPreviewService
import com.kachat.app.services.LinkPreviewService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.MessageProtocol

private val VIDEO_HOSTS = setOf("youtube.com", "www.youtube.com", "youtu.be", "m.youtube.com")

/** Rich link-preview card shown below a chat bubble's text when the message contains a link -
 *  mirrors iMessage. Renders nothing while the fetch is in flight, rather than a placeholder that
 *  could flash or look broken. If no preview data is found (a bare/broken link, or a site with no
 *  Open Graph tags), falls back to [fallbackText] if given, else renders nothing. Used by
 *  [MessageBubble], `GroupMessageBubble`, and broadcast rooms' message rows.
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
    onDoubleTap: (() -> Unit)? = null,
    /** Privacy gate (2026-08 audit, decision 5A, matching iOS): rendering a preview fetches the
     *  stranger-controlled URL from THIS device, revealing the reader's IP and that the message
     *  was seen. True (the default) fetches on render — for accepted/handshaken 1:1 contacts,
     *  group messages, and the user's own sent messages. False renders a "Tap to load preview"
     *  placeholder instead and only fetches after an explicit tap — for non-accepted 1:1
     *  senders and ALL broadcast messages (broadcast senders are always strangers). */
    autoFetch: Boolean = true
) {
    // An in-app KaChat link (a shared KaPosts post or broadcast room) is previewed LOCALLY: no
    // metadata fetch, and therefore no stranger tap-to-load gate either. Handled here rather than
    // only at the call sites so every bubble type that already renders a preview card - 1:1,
    // group and broadcast alike - picks up the https form of the contract for free.
    val internalRef = remember(url) { KaChatLink.parse(url) }
    if (internalRef != null) {
        KaChatInternalLinkCard(
            ref = internalRef,
            url = url,
            txId = txId,
            kaspaExplorer = kaspaExplorer,
            onSelect = onSelect,
            onDoubleTap = onDoubleTap
        )
        return
    }

    var fetchApproved by remember(url, autoFetch) { mutableStateOf(autoFetch) }
    var preview by remember(url) { mutableStateOf<LinkPreviewData?>(null) }
    var hasFinishedLoading by remember(url) { mutableStateOf(false) }

    if (!fetchApproved) {
        TapToLoadPreviewBubble(
            text = fallbackText ?: url,
            url = url,
            txId = txId,
            kaspaExplorer = kaspaExplorer,
            onLoad = { fetchApproved = true },
            onSelect = onSelect,
            onDoubleTap = onDoubleTap
        )
        return
    }

    LaunchedEffect(url, fetchApproved) {
        hasFinishedLoading = false
        preview = LinkPreviewService.fetchPreview(url)
        hasFinishedLoading = true
    }

    if (hasFinishedLoading && preview != null) {
        val data = preview!!
        if (data.nextcloudShareRevoked) {
            // The server says this share is gone. Showing the dead URL would leave the sender's
            // server address in the transcript for a link nobody can open, which is the opposite
            // of why they shared through their own cloud - so the row says only that it is gone.
            RevokedShareTile()
        } else if (data.nextcloudMedia == "image" || data.nextcloudMedia == "video") {
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

/**
 * Stand-in for a Nextcloud share the server no longer serves. Deliberately carries no URL, no
 * host and no tap target - not even a long-press Copy Link. Mirrors iOS's `revokedShareTile`.
 */
@Composable
private fun RevokedShareTile() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = LocalAppColors.current.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "This file is no longer shared",
            color = LocalAppColors.current.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Placeholder shown instead of an auto-fetched preview when [LinkPreviewCard]'s `autoFetch` gate
 * is off (non-accepted 1:1 senders, all broadcast messages): the raw message/link text in a plain
 * bubble with a "Tap to load preview" caption. Tapping fetches and swaps in the real preview;
 * until then no request of any kind leaves the device for this URL. Long-press menu matches
 * [LinkPreviewFallbackBubble]'s exactly.
 */
@Composable
private fun TapToLoadPreviewBubble(
    text: String,
    url: String,
    txId: String,
    kaspaExplorer: KaspaExplorer,
    onLoad: () -> Unit,
    onSelect: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
            .pointerInput(url) {
                detectTapGestures(
                    onLongPress = { showMenu = true },
                    onDoubleTap = { onDoubleTap?.invoke() },
                    onTap = { onLoad() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = LocalAppColors.current.textPrimary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Public,
                contentDescription = null,
                tint = LocalAppColors.current.textSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Tap to load preview",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.labelSmall
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
    val context = LocalContext.current
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
                // Load the OG image with a real browser UA + Referer (the page it came from).
                // Image CDNs like cdninstagram/fbcdn 403 the header-less request Coil sends by
                // default, which is why Instagram previews showed no picture. Mirrors iOS's
                // LinkPreviewService.imageData(referer:).
                val imageRequest = remember(data.imageUrl, data.url) {
                    ImageRequest.Builder(context)
                        .data(data.imageUrl)
                        .addHeader("User-Agent", LinkPreviewService.BROWSER_USER_AGENT)
                        .addHeader("Referer", data.url)
                        .crossfade(true)
                        .build()
                }
                SubcomposeAsyncImage(
                    model = imageRequest,
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

// ---------------------------------------------------------------------------------------------
// In-app KaChat links (shared with iOS - both platforms emit and accept exactly these forms)
// ---------------------------------------------------------------------------------------------

/**
 * A KaChat link found in message text. These are the ONLY links that never touch the network for
 * a preview: everything the card shows is built from the link itself plus whatever text it was
 * pasted with, and tapping one routes inside the app instead of handing the URL to a browser.
 *
 * Wire forms (identical on iOS):
 *   KaPosts post   kachat://kapost/<txid>       https://kachat.duckdns.org/post/<txid>
 *   Broadcast room kachat://broadcast/<channel> https://kachat.duckdns.org/broadcast/<channel>
 *
 * `<channel>` is the normalized channel name with NO leading '#'
 * (see [MessageProtocol.normalizeChannelName]).
 */
sealed class KaChatLinkRef {
    data class KaPost(val txId: String) : KaChatLinkRef()
    data class BroadcastRoom(val channel: String) : KaChatLinkRef()
}

/** [raw] is the exact link substring as it appears in the message, [range] where it sits in it. */
data class KaChatLinkMatch(val range: IntRange, val raw: String, val ref: KaChatLinkRef)

object KaChatLink {
    const val WEB_HOST = "kachat.duckdns.org"

    fun kaPostUrl(txId: String) = "kachat://kapost/$txId"
    fun kaPostWebUrl(txId: String) = "https://$WEB_HOST/post/$txId"
    fun broadcastUrl(channel: String) = "kachat://broadcast/$channel"
    fun broadcastWebUrl(channel: String) = "https://$WEB_HOST/broadcast/$channel"

    // The trailing segment deliberately excludes '/', '?' and '#' so a link can never carry a
    // second path component, a query string or a fragment into the app.
    private val LINK_REGEX = Regex(
        """(?:kachat://(kapost|broadcast)/|https?://kachat\.duckdns\.org/(post|broadcast)/)([^\s/?#]+)""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', '\'', '"', ')', ']', '}', '>')
    private val TX_ID_REGEX = Regex("[0-9a-fA-F]{6,64}")

    /** True when [raw] is nothing but one of these links (no surrounding text). */
    fun parse(raw: String): KaChatLinkRef? {
        val match = LINK_REGEX.matchEntire(raw.trim()) ?: return null
        return refFor(match)
    }

    /** The first valid KaChat link in [text], or null. Invalid ones are skipped, not accepted. */
    fun findFirst(text: String): KaChatLinkMatch? {
        for (match in LINK_REGEX.findAll(text)) {
            var end = match.range.last
            while (end >= match.range.first && text[end] in TRAILING_PUNCTUATION) end--
            if (end < match.range.first) continue
            val range = match.range.first..end
            val raw = text.substring(range.first, range.last + 1)
            val ref = parse(raw) ?: continue
            return KaChatLinkMatch(range, raw, ref)
        }
        return null
    }

    /**
     * Normalizes an incoming broadcast channel name from an UNTRUSTED source (a pasted link, an
     * inbound intent), returning null when it is malformed or hostile rather than joining it.
     *
     * [MessageProtocol.isValidChannelName] is the protocol gate (non-blank, no whitespace, no
     * colons, within the length cap); on top of it a link-borne name must also survive being put
     * in a navigation route and rendered as a room title, so anything that could re-encode as
     * another URL, split the route, or hide characters in the title is rejected too.
     */
    fun sanitizeChannelName(raw: String): String? {
        val name = MessageProtocol.normalizeChannelName(raw.trim().removePrefix("#"))
        if (!MessageProtocol.isValidChannelName(name)) return null
        if (name.any { it in "/?#%&\\" || it.isISOControl() }) return null
        return name
    }

    private fun refFor(match: MatchResult): KaChatLinkRef? {
        val kind = match.groupValues[1].ifEmpty { match.groupValues[2] }.lowercase()
        // Percent-decoding only (NOT URLDecoder, which would turn a legal '+' in a channel name
        // into a space).
        val segment = try {
            android.net.Uri.decode(match.groupValues[3])
        } catch (e: Exception) {
            null
        } ?: return null
        return when (kind) {
            "kapost", "post" ->
                segment.takeIf { TX_ID_REGEX.matches(it) }?.lowercase()?.let(KaChatLinkRef::KaPost)
            "broadcast" ->
                sanitizeChannelName(segment)?.let(KaChatLinkRef::BroadcastRoom)
            else -> null
        }
    }
}

/**
 * Hands an in-app link to the app's own routing instead of the browser. Both paths land in the
 * same deep-link holders a notification tap uses, so Child Mode is enforced in exactly one place
 * (MainShell's deep-link effects) for taps, notifications and system intents alike.
 */
fun openKaChatLink(ref: KaChatLinkRef) {
    when (ref) {
        is KaChatLinkRef.KaPost -> {
            // Focus first, post id second - the post id is what KaPostsScreen keys on.
            KaPostsDeepLink.pendingFocusReplyTxId.value = null
            KaPostsDeepLink.pendingPostTxId.value = ref.txId
        }
        is KaChatLinkRef.BroadcastRoom -> BroadcastDeepLink.request(ref.channel)
    }
}

/**
 * The rich preview for an in-app KaChat link: the link's own identity (KaPosts post / broadcast
 * room), and for a post the author and text themselves, resolved from the chain by
 * [KaPostLinkPreviewService] - a shared post previews AS the post rather than as a URL.
 *
 * The only request that leaves the device is that one, to Kaspa's own REST API with a transaction
 * id. The link's host is never contacted, so there is no "tap to load" gate: a stranger's KaChat
 * link is as safe to render as your own.
 *
 * Long-press menu matches every other preview card's.
 */
@Composable
fun KaChatInternalLinkCard(
    ref: KaChatLinkRef,
    url: String,
    txId: String,
    kaspaExplorer: KaspaExplorer = KaspaExplorer.default,
    onSelect: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

    val postId = (ref as? KaChatLinkRef.KaPost)?.txId
    val previews by KaPostLinkPreviewService.previews.collectAsState()
    val post = postId?.let { previews[it] }
    // Idempotent - the service drops a repeat call, an in-flight one and a known-bad id.
    LaunchedEffect(postId) { postId?.let { KaPostLinkPreviewService.load(it) } }

    val icon = when (ref) {
        is KaChatLinkRef.KaPost -> Icons.Default.NoteAlt
        is KaChatLinkRef.BroadcastRoom -> Icons.Default.Sensors
    }
    val title = when (ref) {
        is KaChatLinkRef.KaPost -> post?.authorName ?: "KaPosts post"
        is KaChatLinkRef.BroadcastRoom -> "#${ref.channel}"
    }
    val body = post?.snippet?.takeIf { it.isNotBlank() }
        // A quote with no added comment is a repost: there is no text to show, so say what it is
        // rather than leaving the card looking half-loaded.
        ?: (post?.takeIf { it.action == "quote" }?.let { "Reposted a post." })
        ?: when (ref) {
            is KaChatLinkRef.KaPost -> "Tap to open this post in KaChat"
            is KaChatLinkRef.BroadcastRoom -> "Tap to join this broadcast room"
        }
    val caption = when (ref) {
        // A reply or a quote is still a KaPosts post, but saying which one it is explains why
        // the text may read as half a conversation.
        is KaChatLinkRef.KaPost -> when (post?.action) {
            "reply" -> "KAPOSTS REPLY"
            "quote" -> "KAPOSTS QUOTE"
            else -> "KAPOSTS"
        }
        is KaChatLinkRef.BroadcastRoom -> "BROADCAST ROOM"
    }
    // The text of a real post deserves more than the placeholder's two lines.
    val bodyMaxLines = if (post?.snippet?.isNotBlank() == true) 6 else 2

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
                    onTap = { openKaChatLink(ref) }
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // A resolved post shows its AUTHOR, the same avatar the KaPosts feed draws for them. The
        // KaPosts glyph is for a post that has not resolved yet, and for room invites, which have
        // no author at all.
        val authorAddress = post?.authorAddress?.takeIf { it.isNotEmpty() }
        if (authorAddress != null) {
            ContactAvatar(
                imageUrl = post.authorAvatarUrl,
                fallbackText = post.authorName ?: "",
                size = 34.dp,
                fontSize = 14.sp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(KaspaTeal.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                body,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = bodyMaxLines,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                caption,
                color = KaspaTeal,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
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
}
