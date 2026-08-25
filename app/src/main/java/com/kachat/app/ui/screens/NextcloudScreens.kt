package com.kachat.app.ui.screens

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
            errorMessage = e.message ?: "Could not load this folder."
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
                    errorMessage = e.message ?: "Could not create a share link."
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (pathStack.size > 1) pathStack = pathStack.dropLast(1) else onDismiss()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = KaspaTeal)
                }
                Text(
                    text = currentPath.substringAfterLast('/').ifEmpty { "Nextcloud" },
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (startFolder.isNotEmpty() && pathStack.first().isNotEmpty()) {
                    TextButton(onClick = { pathStack = listOf("") }) {
                        Text("All Files", color = KaspaTeal)
                    }
                }
            }

            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = Color(0xFFFF3B30),
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
                    CircularProgressIndicator(
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
            val size = file.size?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
            if (size != null) {
                Text(size, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (isSharing) {
            CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
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
            errorMessage = e.message ?: "Could not load this folder."
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (pathStack.size > 1) pathStack = pathStack.dropLast(1) else onDismiss()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = KaspaTeal)
                }
                Text(
                    text = currentPath.substringAfterLast('/').ifEmpty { "All Files" },
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onSelect(currentPath.takeIf { it.isNotEmpty() }) }) {
                    Text(
                        if (currentPath.isEmpty()) "Use All Files" else "Use This Folder",
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = Color(0xFFFF3B30),
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
                    CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/**
 * Full-screen viewer for a tapped Nextcloud photo card — fetches the ORIGINAL file through the
 * public `/download` endpoint (no credentials needed on the recipient side), with pinch-zoom +
 * pan. The top-right button opens the underlying share link in the browser. Videos never come
 * here — they hand off to the system player via ACTION_VIEW instead.
 */
@Composable
fun NextcloudPhotoViewerDialog(
    downloadUrl: String,
    shareUrl: String,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
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
                        CircularProgressIndicator(color = Color.White)
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
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { uriHandler.openUri(shareUrl) }) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Full-screen in-app viewer for a tapped Nextcloud PDF share — downloads the file to app cache
 * (public `/download` endpoint, no credentials needed), then renders every page as a bitmap in a
 * scrollable column via the platform's own [android.graphics.pdf.PdfRenderer] — no new
 * dependency. The top-right button opens the underlying share link in the browser.
 */
@Composable
fun NextcloudPdfViewerDialog(
    downloadUrl: String,
    shareUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
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
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Could not load this file.", color = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { uriHandler.openUri(shareUrl) }) {
                            Text("Open in Nextcloud", color = KaspaTeal)
                        }
                    }
                }
                else -> {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
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
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { uriHandler.openUri(shareUrl) }) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser", tint = Color.White)
                }
            }
        }
    }
}

/**
 * The rows of the Settings > Storage > Nextcloud page ([NextcloudStorageScreen] supplies the
 * page chrome). Not connected: server/username/app-password form. Connected: account line,
 * start/backup folder rows, automatic-backup toggle, manual backup, last-backup line, restore
 * (with confirmation), disconnect. Mirrors iOS's NextcloudSettingsView, which its Storage page
 * pushes as its own screen in exactly the same way.
 *
 * Card title is left null because the page's app bar already says "Nextcloud".
 */
@Composable
fun NextcloudSettingsSection(chatViewModel: ChatViewModel) {
    val context = LocalContext.current
    val service = chatViewModel.nextcloud
    val account by service.account.collectAsState()
    val autoBackupEnabled by service.autoBackupEnabled.collectAsState()
    val lastAutoSyncMs by chatViewModel.nextcloudLastAutoSyncMs.collectAsState()
    val mediaSendEnabled by service.mediaSendEnabled.collectAsState()
    val connectState by chatViewModel.nextcloudConnectState.collectAsState()
    val backupState by chatViewModel.nextcloudBackupState.collectAsState()
    val restorePhase by chatViewModel.restoreCoordinator.phase.collectAsState()
    val backupInfo by chatViewModel.nextcloudBackupInfo.collectAsState()

    var showStartFolderPicker by remember { mutableStateOf(false) }
    var showBackupFolderPicker by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(account) {
        if (account != null) chatViewModel.refreshNextcloudBackupInfo()
    }

    SettingsSection(title = null) {
        val connected = account
        if (connected == null) {
            var server by remember { mutableStateOf("") }
            var username by remember { mutableStateOf("") }
            var appPassword by remember { mutableStateOf("") }
            val connecting = connectState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS

            Column(modifier = Modifier.padding(16.dp)) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    focusedBorderColor = KaspaTeal,
                    unfocusedBorderColor = LocalAppColors.current.textSecondary,
                    focusedLabelColor = KaspaTeal,
                    unfocusedLabelColor = LocalAppColors.current.textSecondary
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server (e.g. cloud.example.com)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // Same reveal-toggle field the Child Mode password flows use - app passwords are
                // long random strings, so being able to see what was typed/pasted matters here.
                RevealableSecureField(
                    value = appPassword,
                    onValueChange = { appPassword = it },
                    label = "App password",
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { chatViewModel.connectNextcloud(server, username, appPassword) },
                    enabled = !connecting && server.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (connecting) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                if (connectState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        connectState.message ?: "Could not connect",
                        color = Color(0xFFFF3B30),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            SettingsFooter("Connect your own Nextcloud server to send photos and videos as share links and back up chat history. Create an app password in Nextcloud under Settings > Security.")
        } else {
            SettingsInfoItem(label = "Connected", value = connected.displayName)
            SettingsDivider()
            SettingsInfoItem(
                label = "Start Folder",
                value = connected.startFolder?.substringAfterLast('/') ?: "All Files",
                onClick = { showStartFolderPicker = true }
            )
            SettingsDivider()
            SettingsInfoItem(
                label = "Backup Folder",
                value = connected.backupFolder ?: "KaChat (default)",
                onClick = { showBackupFolderPicker = true }
            )
            SettingsDivider()
            SettingsSwitchItem("Automatic Sync", autoBackupEnabled) { enabled ->
                chatViewModel.setNextcloudAutoSyncEnabled(enabled)
            }
            SettingsFooter(
                (if (autoBackupEnabled) {
                    val lastSyncedText = lastAutoSyncMs?.let {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                    }
                    "Messages sync to this server shortly after sending or receiving, merging with backups from your other devices. While KaChat is open, new messages from your other devices appear here automatically, fastest while you are in a chat. " +
                        if (lastSyncedText != null) "Last synced: $lastSyncedText." else "Waiting for the first sync."
                } else {
                    "Turn on to keep this server's backup current automatically and to mirror new messages between your devices in near real time while the app is open."
                }) + " Automatic sync works with one cloud service at a time."
            )
            SettingsDivider()
            SettingsSwitchItem("Send Media via Nextcloud", mediaSendEnabled) { enabled ->
                service.setMediaSendEnabled(enabled)
            }
            SettingsFooter(
                "When on, photos and voice messages you send in chats upload to your Nextcloud at full quality and the chat carries a share link instead. The file is stored unencrypted on your own server behind an unguessable link — the message carrying the link stays end-to-end encrypted. When off, media is embedded in the encrypted on-chain payload as before."
            )

            val backupInFlight = backupState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS
            val restoreInFlight = restorePhase is com.kachat.app.services.BackupRestoreCoordinator.Phase.Running

            SettingsDivider()
            SettingsActionItem(
                label = if (backupInFlight) "Backing Up..." else "Back Up Messages Now",
                icon = Icons.Default.CloudUpload,
                color = if (backupInFlight) Color.Gray else KaspaTeal
            ) {
                if (!backupInFlight) chatViewModel.nextcloudBackupNow()
            }
            val lastBackupText = backupInfo?.let { info ->
                val date = info.modifiedMs?.let {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                }
                val size = info.size?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
                "Last backup: ${date ?: "unknown date"}${if (size != null) " · $size" else ""}"
            }
            SettingsFooter(
                when {
                    backupInFlight -> "Working..."
                    backupState.status == ChatViewModel.ChatHistoryOpStatus.FAILED -> backupState.message ?: "Backup failed"
                    lastBackupText != null -> lastBackupText
                    else -> "No backup on this server yet."
                }
            )

            SettingsDivider()
            SettingsActionItem(
                label = if (restoreInFlight) "Restoring..." else "Restore from Nextcloud",
                icon = Icons.Default.CloudDownload,
                color = if (restoreInFlight) Color.Gray else KaspaTeal
            ) {
                // Progress and terminal states (success/failure) show in the blocking restore
                // modal (ChatRestoreProgressOverlay), not as footer rows here.
                if (!restoreInFlight) showRestoreConfirm = true
            }

            SettingsDivider()
            SettingsActionItem(
                label = "Disconnect",
                icon = Icons.Default.CloudOff,
                color = Color(0xFFFF3B30)
            ) {
                chatViewModel.disconnectNextcloud()
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
                chatViewModel.refreshNextcloudBackupInfo()
            }
        )
    }
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Restore from Nextcloud?", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "This merges the backup on your Nextcloud server into this device's chat history. Nothing is deleted.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    chatViewModel.restoreFromNextcloud()
                }) {
                    Text("Restore", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}
