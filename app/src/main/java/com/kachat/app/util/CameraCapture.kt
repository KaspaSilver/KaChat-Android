package com.kachat.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** A fresh destination file for `ActivityResultContracts.TakePicture()` to capture into, exposed
 *  via the same FileProvider authority already used for chat/diagnostics/portfolio exports
 *  (`AndroidManifest.xml`'s `${applicationId}.fileprovider`, `res/xml/file_paths.xml`). */
fun createCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Bundles the permission-check -> create-destination-file -> launch-camera boilerplate behind a
 * single trigger function, so 1:1 chat and group chat's "+" menus can both call one shared helper
 * instead of duplicating the same launcher/permission plumbing. Mirrors the permission-check
 * pattern already used for the QR scanner (`QrScannerScreen.kt`'s
 * `ContextCompat.checkSelfPermission`/`RequestPermission()`) and the inline
 * `startVoiceRecordingIfPermitted`-style trigger shape already used for the composer's Audio
 * Message option (`Screens.kt`).
 *
 * Asks for the camera first ([MediaAccess.CAMERA]) and only opens once it's allowed; with access
 * off for good it calls [onDenied] instead, for the composer's [MediaAccessAlert] (iOS ca013fc8).
 */
@Composable
fun rememberCameraCaptureLauncher(onDenied: (MediaAccess) -> Unit = {}, onCaptured: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            pendingCaptureUri?.let(onCaptured)
        }
        pendingCaptureUri = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // A fresh Don't Allow just closes: they've only now said no.
        if (granted) {
            val uri = createCaptureUri(context)
            pendingCaptureUri = uri
            cameraLauncher.launch(uri)
        }
    }

    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val uri = createCaptureUri(context)
            pendingCaptureUri = uri
            cameraLauncher.launch(uri)
        } else {
            MediaAccess.CAMERA.request(context, permissionLauncher, onDenied)
        }
    }
}
