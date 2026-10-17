package com.kachat.app.ui.screens

import com.kachat.app.util.UserFacingError
import com.kachat.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.kachat.app.services.NextcloudFile
import com.kachat.app.services.NextcloudService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.ChatViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import com.kachat.app.util.is24HourClock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DateFormat
import java.util.Date

// "Send from Nextcloud" picker + Settings > Storage > Nextcloud UI — mirrors iOS's
// NextcloudPickerView.swift / the Nextcloud settings section. Browses the connected account's
// files over WebDAV; picking a photo/video asks the server for a public /s/TOKEN share link via
// OCS — the caller sends that link as a normal chat message, which the recipient's link-preview
// feature renders as tappable media.

/**
 * Full-screen "Send from Nextcloud" picker. Browsing starts at the account's configured start
 * folder, with an "All Files" escape hatch back to the root. Folders list as full-width rows;
 * photos/videos render as a Photos-style square thumbnail grid fed by the server's `core/preview`
 * endpoint. Tapping a media cell creates (or reuses) its public share link, then hands the link
 * to [onPick] — the caller sends it and dismisses.
 */
@Composable
fun NextcloudPickerDialog(
    service: NextcloudService,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val account by service.account.collectAsState()
    val startFolder = account?.startFolder ?: ""
    // In-dialog navigation: a plain path stack instead of a nav graph — back pops one level.
    var pathStack by remember { mutableStateOf(listOf(startFolder)) }
    val currentPath = pathStack.last()

    var entries by remember { mutableStateOf<List<NextcloudFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Path of the file whose share link is currently being created — shows that cell's spinner
    // and blocks double-picks.
    var sharingPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        isLoading = true
        errorMessage = null
        entries = try {
            service.listFolder(currentPath)
        } catch (e: Exception) {
            errorMessage = UserFacingError.message(e, "Could not load this folder.")
            emptyList()
        }
        isLoading = false
    }

    // listFolder already returns newest-first (folders grouped ahead of files), so these only split
    // that one ordering into sections — filtering preserves it, never re-sort here.
    val folders = entries.filter { it.isDirectory }
    val media = entries.filter { it.isImage || it.isVideo }
    // Everything else (audio, PDFs, docs, archives...) is still shareable — listed as rows below
    // the thumbnail grid, shared exactly like media.
    val otherFiles = entries.filter { !it.isDirectory && !it.isImage && !it.isVideo }

    val share: (NextcloudFile) -> Unit = { file ->
        if (sharingPath == null) {
            sharingPath = file.path
            scope.launch {
                try {
                    val url = service.createPublicShareLink(file.path)
                    sharingPath = null
                    onPick(url)
                } catch (e: Exception) {
                    sharingPath = null
                    errorMessage = UserFacingError.message(e, "Could not create a share link.")
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalAppColors.current.background)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // iOS 26's bar: the back chevron on a glass disc and the action on a glass capsule,
            // 16 in from the edges.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetBarBackButton({
                    if (pathStack.size > 1) pathStack = pathStack.dropLast(1) else onDismiss()
                })
                Text(
                    text = currentPath.substringAfterLast('/').ifEmpty { "Nextcloud" },
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                if (startFolder.isNotEmpty() && pathStack.first().isNotEmpty()) {
                    SheetBarButton("All Files", onClick = { pathStack = listOf("") })
                }
            }

            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = LocalAppColors.current.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(3.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    folders.forEach { folder ->
                        item(key = "dir:${folder.path}", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pathStack = pathStack + folder.path }
                                    .padding(horizontal = 13.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    folder.name,
                                    color = LocalAppColors.current.textPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    tint = LocalAppColors.current.textSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                    items(media, key = { it.path }) { file ->
                        NextcloudThumbnailCell(
                            service = service,
                            file = file,
                            isSharing = sharingPath == file.path,
                            onClick = { share(file) }
                        )
                    }
                    otherFiles.forEach { file ->
                        item(key = "file:${file.path}", span = { GridItemSpan(maxLineSpan) }) {
                            NextcloudFileRow(
                                file = file,
                                isSharing = sharingPath == file.path,
                                onClick = { share(file) }
                            )
                        }
                    }
                    if (!isLoading && folders.isEmpty() && media.isEmpty() && otherFiles.isEmpty() && errorMessage == null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "This folder is empty.",
                                color = LocalAppColors.current.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                if (isLoading && entries.isEmpty()) {
                    com.kachat.app.ui.theme.IosActivityIndicator(
                        color = KaspaTeal,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/** One tappable row for a non-media file (audio/PDF/doc/...): type icon, name, size — shared
 *  exactly like a media cell, with the same in-flight spinner while its link is created. */
@Composable
private fun NextcloudFileRow(
    file: NextcloudFile,
    isSharing: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val extension = file.path.substringAfterLast('.', "").lowercase()
    val icon = when {
        file.contentType?.startsWith("audio/") == true || extension in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav") -> Icons.Default.GraphicEq
        file.contentType?.contains("pdf") == true || extension == "pdf" -> Icons.Default.PictureAsPdf
        else -> Icons.Default.Description
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                color = LocalAppColors.current.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val size = file.size?.let { com.kachat.app.util.IosByteCount.format(context, it) }
            if (size != null) {
                Text(size, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (isSharing) {
            com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

/** One square grid cell: server-side thumbnail when available (authenticated `core/preview`
 *  request via Coil), media-type icon otherwise, play badge for videos, spinner overlay while
 *  its share link is being created. */
@Composable
private fun NextcloudThumbnailCell(
    service: NextcloudService,
    file: NextcloudFile,
    isSharing: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumb = remember(file.path) { service.thumbnailRequest(file.path) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val placeholder: @Composable () -> Unit = {
            Icon(
                if (file.isVideo) Icons.Default.Movie else Icons.Default.Photo,
                contentDescription = null,
                tint = LocalAppColors.current.textSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
        if (thumb != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumb.url)
                    .addHeader("Authorization", thumb.authorization)
                    .build(),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize()) },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { placeholder() } }
            )
        } else {
            placeholder()
        }
        if (file.isVideo) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (isSharing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * Folder-only browser for choosing the picker's start folder or the backup destination.
 * Selecting the root reports null (= All Files / default backup folder).
 */
@Composable
fun NextcloudFolderSelectDialog(
    service: NextcloudService,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    var pathStack by remember { mutableStateOf(listOf("")) }
    val currentPath = pathStack.last()
    var folders by remember { mutableStateOf<List<NextcloudFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        isLoading = true
        errorMessage = null
        folders = try {
            // Newest-first comes from listFolder; this only drops the non-folder entries.
            service.listFolder(currentPath).filter { it.isDirectory }
        } catch (e: Exception) {
            errorMessage = UserFacingError.message(e, "Could not load this folder.")
            emptyList()
        }
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalAppColors.current.background)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // iOS 26's bar: the back chevron on a glass disc and the action on a glass capsule,
            // 16 in from the edges.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetBarBackButton({
                    if (pathStack.size > 1) pathStack = pathStack.dropLast(1) else onDismiss()
                })
                Text(
                    text = currentPath.substringAfterLast('/').ifEmpty { "All Files" },
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                SheetBarButton(
                    if (currentPath.isEmpty()) "Use All Files" else "Use This Folder",
                    onClick = { onSelect(currentPath.takeIf { it.isNotEmpty() }) },
                    emphasized = true,
                )
            }

            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = LocalAppColors.current.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    lazyListItems(folders, key = { it.path }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pathStack = pathStack + folder.path }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                folder.name,
                                color = LocalAppColors.current.textPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = LocalAppColors.current.textSecondary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    if (!isLoading && folders.isEmpty() && errorMessage == null) {
                        item {
                            Text(
                                "No subfolders.",
                                color = LocalAppColors.current.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                if (isLoading && folders.isEmpty()) {
                    com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/**
 * Full-screen viewer for a tapped Nextcloud photo card — fetches the ORIGINAL file through the
 * public `/download` endpoint (no credentials needed on the recipient side), with pinch-zoom +
 * pan. It is not given the share URL at all: sharing through your own cloud is a privacy
 * choice, and there is no reason the viewer should be able to hand that link to a browser.
 * Videos never come here — they hand off to the system player via ACTION_VIEW instead.
 */
@Composable
fun NextcloudPhotoViewerDialog(
    downloadUrl: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 6f)
            // Snap pan back when fully zoomed out so the photo can't be flung off-screen.
            offset = if (scale <= 1f) Offset.Zero else offset + panChange
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            SubcomposeAsyncImage(
                model = downloadUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White)
                    }
                },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Could not load this file.", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(transformState)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Full-screen in-app viewer for a tapped Nextcloud PDF share — downloads the file to app cache
 * (public `/download` endpoint, no credentials needed), then renders every page as a bitmap in a
 * scrollable column via the platform's own [android.graphics.pdf.PdfRenderer] — no new
 * dependency. There is deliberately no way out to the share link: the point of sharing through
 * your own cloud is that the URL stays between the two of you.
 */
@Composable
fun NextcloudPdfViewerDialog(
    downloadUrl: String,
    shareUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<List<android.graphics.Bitmap>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(downloadUrl) {
        pages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = com.kachat.app.services.LinkPreviewService.downloadToCacheFile(context, downloadUrl)
                    ?: return@withContext null
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                        (0 until renderer.pageCount).map { index ->
                            val page = renderer.openPage(index)
                            try {
                                // Render at 2x the page's point size (capped) so text stays
                                // legible when the column scales pages to screen width.
                                val width = (page.width * 2).coerceAtMost(2048)
                                val height = (page.height.toLong() * width / page.width).toInt()
                                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bitmap
                            } finally {
                                page.close()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        if (pages == null) loadFailed = true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val renderedPages = pages
            when {
                renderedPages != null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 64.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(renderedPages) { _, page ->
                            Image(
                                bitmap = page.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                loadFailed -> {
                    // No "Open in Nextcloud" escape hatch. A file that fails to load here has
                    // almost always lost its share, and that link opened a browser onto the
                    // same dead share - a second failure dressed as an option.
                    Text(
                        "Could not load this file.",
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

/**
 * The sections of the Settings > Storage > Nextcloud page ([NextcloudStorageScreen] supplies the
 * Form page around them) - iOS's NextcloudSettingsView, section for section.
 *
 * Not connected: "Server" (address, username and app password as plain rows in one card, the
 * https warning between them, the app-password footer), an error section when connecting failed,
 * and Connect in a section of its own.
 *
 * Connected: Connected Account (server, username); Start Folder with the + menu footer; Message
 * Backup (Automatic Sync, the one-service note, Last synced, Backup Folder, Back Up Messages Now,
 * Restore from Backup, the last backup, the outcome of a backup started here) with its footer;
 * Disconnect, confirmed by an alert, with its footer.
 */
@Composable
fun NextcloudSettingsSection(chatViewModel: ChatViewModel) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val service = chatViewModel.nextcloud
    val account by service.account.collectAsState()
    val autoBackupEnabled by service.autoBackupEnabled.collectAsState()
    val lastAutoSyncMs by chatViewModel.nextcloudLastAutoSyncMs.collectAsState()
    val connectState by chatViewModel.nextcloudConnectState.collectAsState()
    val backupState by chatViewModel.nextcloudBackupState.collectAsState()
    val restorePhase by chatViewModel.restoreCoordinator.phase.collectAsState()
    val backupInfo by chatViewModel.nextcloudBackupInfo.collectAsState()
    val is24Hour = is24HourClock()

    var showStartFolderPicker by remember { mutableStateOf(false) }
    var showBackupFolderPicker by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    // iOS keeps a backup's outcome ("Backup uploaded." / the error) in the view's own state, so it
    // shows only for a backup started on this visit; the view model's state outlives the page.
    var backupStartedHere by remember { mutableStateOf(false) }

    LaunchedEffect(account) {
        if (account != null) chatViewModel.refreshNextcloudBackupInfo()
    }

    // Testnet: Nextcloud is off until mainnet is back (iOS af8ec68).
    if (service.isOffForTestnet) {
        SettingsFormSection(title = null, footer = stringResource(R.string.nextcloud_off_on_testnet_footer)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.nextcloud_off_on_testnet), color = colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val connected = account
    if (connected == null) {
        var server by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var appPassword by remember { mutableStateOf("") }
        // iOS's errorMessage: the https refusal, or why the connection failed (only for an
        // attempt made on this visit).
        var httpRefused by remember { mutableStateOf(false) }
        var attemptedHere by remember { mutableStateOf(false) }
        val connecting = connectState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS
        val canConnect = server.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank() && !connecting
        // Decision 3A: https only. Bare hostnames are fine (NextcloudService.normalizeServer
        // defaults them to https, and refuses http:// as a backstop); an explicit http:// is
        // flagged inline here and refused again by Connect.
        val serverIsPlainHttp = server.trim().lowercase().startsWith("http://")
        val useHttps = stringResource(R.string.nc_use_https)
        val errorMessage = when {
            httpRefused -> useHttps
            attemptedHere && connectState.status == ChatViewModel.ChatHistoryOpStatus.FAILED ->
                connectState.message ?: "Could not connect"
            else -> null
        }

        NcFormSection(
            title = stringResource(R.string.nc_server),
            footers = listOf(stringResource(R.string.nc_app_password_footer)),
        ) {
            NcFormField(
                value = server,
                onValueChange = { server = it },
                placeholder = stringResource(R.string.nc_server_placeholder),
                keyboardType = KeyboardType.Uri,
            )
            if (serverIsPlainHttp) {
                SettingsDivider()
                NcCaptionRow(useHttps, colors.danger)
            }
            SettingsDivider()
            NcFormField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.nc_username),
            )
            SettingsDivider()
            NcFormField(
                value = appPassword,
                onValueChange = { appPassword = it },
                placeholder = stringResource(R.string.nc_app_password),
                secure = true,
            )
        }

        if (errorMessage != null) {
            SettingsFormSection(title = null) {
                NcCaptionRow(errorMessage, colors.danger)
            }
        }

        SettingsFormSection(title = null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canConnect) {
                        if (server.trim().lowercase().startsWith("http://")) {
                            httpRefused = true
                        } else {
                            httpRefused = false
                            attemptedHere = true
                            chatViewModel.connectNextcloud(server, username, appPassword)
                        }
                    }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                if (connecting) {
                    com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        stringResource(R.string.nc_connect),
                        color = if (canConnect) KaspaTeal else ncTertiaryLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    } else {
        val backupInFlight = backupState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS
        val restoreInFlight = restorePhase is com.kachat.app.services.BackupRestoreCoordinator.Phase.Running
        val host = connected.server.toHttpUrlOrNull()?.host ?: connected.server
        val allFiles = stringResource(R.string.pnc_all_files)

        SettingsFormSection(title = stringResource(R.string.nc_connected_account)) {
            NcValueRow(stringResource(R.string.nc_server), host, truncation = NcTruncation.MIDDLE)
            SettingsDivider()
            NcValueRow(stringResource(R.string.nc_username), connected.username)
        }

        // The old "Send Media via Nextcloud" switch is gone: Camera, Photo and Voice Message in a
        // chat's + menu ask on chain or via Nextcloud every time. What that choice means for
        // privacy still belongs here (iOS 8b13460).
        NcFormSection(
            title = null,
            footers = listOf(
                stringResource(R.string.nextcloud_file_folder_footer),
                stringResource(R.string.nextcloud_media_route_footer, NextcloudService.MEDIA_FOLDER_PATH),
            ),
        ) {
            NcValueRow(
                stringResource(R.string.nc_start_folder),
                connected.startFolder ?: allFiles,
                truncation = NcTruncation.HEAD,
                onClick = { showStartFolderPicker = true }
            )
        }

        NcFormSection(
            title = stringResource(R.string.nc_message_backup),
            footers = listOf(
                stringResource(R.string.nc_message_backup_footer, NextcloudService.BACKUP_FILE_NAME, NextcloudService.BACKUP_FOLDER_NAME),
                stringResource(R.string.nextcloud_sync_address_book_portfolios),
            ),
        ) {
            SettingsSwitchItem(stringResource(R.string.nc_automatic_sync), autoBackupEnabled) { enabled ->
                chatViewModel.setNextcloudAutoSyncEnabled(enabled)
            }
            SettingsDivider()
            NcCaptionRow(stringResource(R.string.nc_auto_sync_one_service), colors.textSecondary)
            lastAutoSyncMs?.let { ms ->
                SettingsDivider()
                NcValueRow(
                    stringResource(R.string.nc_last_synced),
                    com.kachat.app.util.IosDateStyle.mediumDateShortTime(ms, is24Hour),
                    caption = true
                )
            }
            SettingsDivider()
            NcValueRow(
                stringResource(R.string.nc_backup_folder),
                connected.backupFolder ?: stringResource(R.string.nc_default_folder, NextcloudService.BACKUP_FOLDER_NAME),
                truncation = NcTruncation.HEAD,
                onClick = { showBackupFolderPicker = true }
            )
            SettingsDivider()
            NcLabelButtonRow(
                label = stringResource(R.string.nc_back_up_now),
                icon = Icons.Outlined.UploadFile,
                enabled = !backupInFlight && !restoreInFlight,
                busy = backupInFlight,
            ) {
                backupStartedHere = true
                chatViewModel.nextcloudBackupNow()
            }
            SettingsDivider(inset = IosRowTextInset)
            NcLabelButtonRow(
                label = stringResource(R.string.nc_restore_from_backup),
                icon = Icons.Outlined.FileDownload,
                enabled = !backupInFlight && !restoreInFlight && backupInfo != null,
                busy = restoreInFlight,
            ) {
                // Progress and the outcome show in the blocking restore modal
                // (ChatRestoreProgressOverlay), not as rows here.
                showRestoreConfirm = true
            }
            val info = backupInfo
            val modified = info?.modifiedMs
            if (info != null && modified != null) {
                SettingsDivider(inset = IosRowTextInset)
                val size = info.size?.let { " · " + com.kachat.app.util.IosByteCount.format(context, it) } ?: ""
                NcValueRow(
                    stringResource(R.string.nc_last_backup),
                    com.kachat.app.util.IosDateStyle.mediumDateShortTime(modified, is24Hour) + size,
                    caption = true
                )
            } else if (info == null) {
                SettingsDivider(inset = IosRowTextInset)
                NcCaptionRow(stringResource(R.string.nc_no_backup_yet), colors.textSecondary)
            }
            if (backupStartedHere && backupState.status == ChatViewModel.ChatHistoryOpStatus.SUCCESS) {
                SettingsDivider()
                NcCaptionRow(stringResource(R.string.nc_backup_uploaded), colors.success)
            }
            if (backupStartedHere && backupState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                SettingsDivider()
                NcCaptionRow(backupState.message ?: "Backup failed", colors.danger)
            }
        }

        SettingsFormSection(title = null, footer = stringResource(R.string.nc_disconnect_footer)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDisconnectConfirm = true }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(stringResource(R.string.nc_disconnect), color = colors.danger, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    if (showStartFolderPicker) {
        NextcloudFolderSelectDialog(
            service = service,
            onDismiss = { showStartFolderPicker = false },
            onSelect = { path ->
                service.setStartFolder(path)
                showStartFolderPicker = false
            }
        )
    }
    if (showBackupFolderPicker) {
        NextcloudFolderSelectDialog(
            service = service,
            onDismiss = { showBackupFolderPicker = false },
            onSelect = { path ->
                // Selecting the root resets back to the default "KaChat" folder.
                service.setBackupFolder(path)
                showBackupFolderPicker = false
                // The backup lives per folder - re-check what exists at the new destination.
                chatViewModel.refreshNextcloudBackupInfo()
            }
        )
    }
    if (showRestoreConfirm) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            containerColor = colors.surface,
            title = { Text(stringResource(R.string.nc_restore_from_backup), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.nc_restore_message), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    backupStartedHere = false
                    // Runs behind the blocking progress modal; the coordinator owns the work, so
                    // leaving this page cannot interrupt the restore.
                    chatViewModel.restoreFromNextcloud()
                }) {
                    Text(stringResource(R.string.nc_restore), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = colors.textSecondary)
                }
            }
        )
    }
    if (showDisconnectConfirm) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            containerColor = colors.surface,
            title = { Text(stringResource(R.string.nc_disconnect_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.nc_disconnect_message), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    chatViewModel.disconnectNextcloud()
                }) {
                    Text(stringResource(R.string.nc_disconnect), color = colors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = colors.textSecondary)
                }
            }
        )
    }
}

/** iOS's tertiaryLabel, translucent as iOS draws it: a Form field's placeholder and a disabled
 *  button's title. */
@Composable
private fun ncTertiaryLabel(): Color =
    if (LocalAppColors.current.isDark) Color(0x4DEBEBF5) else Color(0x4D3C3C43)

/**
 * A Form section whose footer is several paragraphs, 6 apart (iOS's footer VStack(spacing: 6));
 * with none it is [SettingsFormSection].
 */
@Composable
private fun NcFormSection(
    title: String?,
    footers: List<String> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (footers.isEmpty()) {
        SettingsFormSection(title = title, content = content)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        SettingsSection(title = title, content = content)
        SettingsFormFooter(footers.first())
        footers.drop(1).forEach { paragraph ->
            Text(
                text = paragraph,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = LocalAppColors.current.textSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)
            )
        }
        Spacer(Modifier.height(IosFormFooterGap))
    }
}

/**
 * A plain Form text field (iOS's TextField / RevealableSecureField in a Form): no box of its own,
 * the row is the field - 17pt text, the placeholder in tertiaryLabel, no capitalisation or
 * autocorrection. [secure] hides the text behind the eye toggle.
 */
@Composable
private fun NcFormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secure: Boolean = false,
) {
    val colors = LocalAppColors.current
    var revealed by remember { mutableStateOf(false) }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = if (secure) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(KaspaTeal),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                keyboardType = if (secure) KeyboardType.Password else keyboardType,
            ),
            visualTransformation = if (secure && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle, color = ncTertiaryLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    innerTextField()
                }
            }
        )
        if (secure) {
            IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (revealed) R.string.hide_password else R.string.show_password),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

private enum class NcTruncation { NONE, MIDDLE, HEAD }

/**
 * A Form row with its title at the left and a value at the right (iOS's HStack { Text; Spacer;
 * Text.foregroundColor(.secondary) }). [caption] writes the value in `.caption` (12pt);
 * [truncation] keeps it on one line, giving way in the middle or at the start. With [onClick]
 * the row is a button.
 */
@Composable
private fun NcValueRow(
    label: String,
    value: String,
    caption: Boolean = false,
    truncation: NcTruncation = NcTruncation.NONE,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val valueStyle = if (caption) {
        MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 16.sp)
    } else {
        MaterialTheme.typography.bodyLarge
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colors.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        val valueModifier = Modifier.weight(1f)
        when (truncation) {
            NcTruncation.MIDDLE -> MiddleEllipsisText(
                value,
                modifier = valueModifier,
                color = colors.textSecondary,
                textAlign = TextAlign.End,
                style = valueStyle,
            )
            NcTruncation.HEAD -> NcHeadEllipsisText(value, valueModifier, colors.textSecondary, valueStyle)
            NcTruncation.NONE -> Text(value, modifier = valueModifier, color = colors.textSecondary, style = valueStyle, textAlign = TextAlign.End)
        }
    }
}

/** One line that gives way at its START when it does not fit - iOS's `.truncationMode(.head)`,
 *  so a folder path keeps the folder's own name. */
@Composable
private fun NcHeadEllipsisText(text: String, modifier: Modifier, color: Color, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    val merged = style.merge(TextStyle(color = color, textAlign = TextAlign.End))
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val shown = remember(text, maxWidth, bounded, merged) {
            val fits = { candidate: String ->
                !measurer.measure(
                    AnnotatedString(candidate),
                    style = merged,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = maxWidth),
                ).hasVisualOverflow
            }
            if (!bounded || fits(text)) {
                text
            } else {
                var low = 0
                var high = text.length - 1
                var best = "…"
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    val candidate = "…" + text.takeLast(mid)
                    if (fits(candidate)) {
                        best = candidate
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                best
            }
        }
        Text(shown, style = merged, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxWidth())
    }
}

/** A row that is only a line of `.caption` text (12pt) in [color] - notes, errors, outcomes. */
@Composable
private fun NcCaptionRow(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = color, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/**
 * A Form button drawn as iOS's Label (symbol, then title) in the accent - tertiaryLabel when
 * disabled - with a spinner at the trailing edge while [busy].
 */
@Composable
private fun NcLabelButtonRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) KaspaTeal else ncTertiaryLabel()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (busy) com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(20.dp))
    }
}

/**
 * Pick one file from the connected Nextcloud (iOS aa5d783, NextcloudFileSelectView): browsing
 * starts in the KaChat folder, with All Files for the root; only [allowedExtensions] can be picked
 * and other files show dimmed. A KaChat folder that doesn't exist yet reads as empty rather than an
 * error.
 */
@Composable
fun NextcloudFileSelectDialog(
    service: NextcloudService,
    allowedExtensions: Set<String>,
    onDismiss: () -> Unit,
    onPick: (NextcloudFile) -> Unit,
) {
    val colors = LocalAppColors.current
    val kachatFolder = service.backupFolderPath.trim('/')
    var pathStack by remember { mutableStateOf(listOf(kachatFolder)) }
    val currentPath = pathStack.last()
    var entries by remember { mutableStateOf<List<NextcloudFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        isLoading = true
        errorMessage = null
        entries = try {
            service.listFolder(currentPath)
        } catch (e: Exception) {
            // The KaChat folder is only made on the first export or backup.
            if (currentPath == kachatFolder && e.message?.contains("404") == true) emptyList()
            else {
                errorMessage = UserFacingError.message(e, "Could not load this folder.")
                emptyList()
            }
        }
        isLoading = false
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding()) {
            // iOS 26's bar: both buttons on glass capsules 16 in, the title in headline.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetBarButton(
                    stringResource(if (pathStack.size > 1) R.string.back else R.string.cancel),
                    onClick = { if (pathStack.size > 1) pathStack = pathStack.dropLast(1) else onDismiss() },
                )
                Text(
                    currentPath.substringAfterLast('/').ifEmpty { stringResource(R.string.pnc_all_files) },
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                SheetBarButton(
                    stringResource(R.string.pnc_all_files),
                    onClick = { if (currentPath.isNotEmpty()) pathStack = pathStack + "" },
                    enabled = currentPath.isNotEmpty(),
                )
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal) }
                errorMessage != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(errorMessage ?: "", color = colors.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.pnc_folder_empty), color = colors.textSecondary)
                }
                else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                    items(entries.size) { index ->
                        val file = entries[index]
                        val pickable = file.isDirectory || file.name.substringAfterLast('.', "").lowercase() in allowedExtensions
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = pickable) {
                                    if (file.isDirectory) pathStack = pathStack + file.path.trim('/') else onPick(file)
                                }
                                .alpha(if (pickable) 1f else 0.4f)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (file.isDirectory) KaspaTeal else colors.textSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(file.name, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                            if (file.isDirectory) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.textSecondary)
                        }
                        HorizontalDivider(Modifier.padding(start = 54.dp), color = colors.divider, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
