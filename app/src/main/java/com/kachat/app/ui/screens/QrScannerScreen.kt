package com.kachat.app.ui.screens

import com.kachat.app.R
import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.QrFrameChunker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX preview bound to a ZXing decode analyzer — [onResult] fires for every decoded frame, as
 * often as the camera delivers one. Requests a higher analysis resolution than CameraX's default
 * (which favors speed over detail) and supports pinch-to-zoom, since a weak/budget camera often
 * just can't resolve a small or distant QR's fine modules at the default settings — this matters
 * most when scanning a QR displayed on another device's screen (KasSigner), whose code density is
 * fixed by its own firmware and isn't something this app can simplify.
 */
@Composable
private fun CameraQrScanner(modifier: Modifier = Modifier, onResult: (Result) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }

    AndroidView(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, _, zoomChange, _ ->
                val cam = camera ?: return@detectTransformGestures
                val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                zoomRatio = (zoomRatio * zoomChange).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                cam.cameraControl.setZoomRatio(zoomRatio)
            }
        },
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // SurfaceView (the default) has its own window surface that can
                // intercept touches meant for sibling Compose overlays like the
                // close button, even when it's declared on top — TextureView
                // composes normally within the view hierarchy's touch dispatch.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                // CameraX's default ImageAnalysis resolution is tuned for throughput, not detail
                // (often ~640x480 on budget hardware) — that's too coarse for a dense QR's modules
                // to survive the analysis downscale, even when the on-screen preview looks sharp.
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    decodeQrCode(imageProxy, onResult)
                }
                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (e: Exception) {
                    // Nothing to scan if the camera fails to bind — user can dismiss and retry.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

/**
 * Recovers the exact original bytes from a decoded byte-mode QR (a KSPT frame), trying a few
 * fallbacks since ZXing's [Result.getRawBytes] is documented as "if applicable" and isn't
 * reliably populated for every QR byte-mode result:
 * 1. [ResultMetadataType.BYTE_SEGMENTS] — the raw per-segment byte data ZXing decoded, before
 *    any charset interpretation; the most direct source when present.
 * 2. [Result.getRawBytes] itself.
 * 3. Re-encoding [Result.getText] as ISO-8859-1 — since the encoder side
 * ([com.kachat.app.ui.screens.rememberQrBitmapPainter]'s ByteArray overload, and KasSigner's own
 * device firmware) both produce byte-mode QR content that ZXing decodes into a `String` via
 * ISO-8859-1 by default, re-encoding that string as ISO-8859-1 round-trips back to the exact
 * original bytes (1 byte : 1 char : 1 byte).
 */
private fun extractRawBytes(result: Result): ByteArray? {
    @Suppress("UNCHECKED_CAST")
    val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
    if (!segments.isNullOrEmpty()) {
        return segments.reduce { acc, segment -> acc + segment }
    }
    result.rawBytes?.let { if (it.isNotEmpty()) return it }
    return result.text?.toByteArray(Charsets.ISO_8859_1)
}

// Restricting to QR (this app never scans anything else) both speeds up MultiFormatReader's
// attempt per frame and avoids it occasionally matching a stray non-QR pattern in the frame.
private val qrOnlyHints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

private fun decodeQrCode(imageProxy: ImageProxy, onResult: (Result) -> Unit) {
    try {
        val buffer = imageProxy.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data, imageProxy.width, imageProxy.height, 0, 0, imageProxy.width, imageProxy.height, false
        )
        val result = try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), qrOnlyHints)
        } catch (e: NotFoundException) {
            // HybridBinarizer's local-contrast threshold can miss a code under uneven lighting
            // (e.g. reading a phone/device screen's own backlight glare) that a simpler global
            // threshold catches — worth one more attempt on the same frame before giving up on it.
            MultiFormatReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), qrOnlyHints)
        }
        onResult(result)
    } catch (e: NotFoundException) {
        // No QR code in this frame — expected for most frames, just wait for the next one.
    } catch (e: Exception) {
        // Decode failure for this frame — ignore and try the next one.
    } finally {
        imageProxy.close()
    }
}

/**
 * The camera QR scanner as iOS presents its `QRScannerView`: a sheet over whatever asked for it
 * (a send's recipient field, a cold storage account, a new chat, a portfolio address), its inline
 * bar reading "Scan QR Code" with Cancel leading, the camera filling the sheet under it, a 250
 * white frame and "Point camera at a QR code" on a frosted plate 50 above the bottom. A scan
 * plays the success haptic, hands the code over and slides the sheet down. A swipe down, Cancel
 * or Back closes it.
 */
@Composable
fun QrScannerSheet(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    val latestScanned by androidx.compose.runtime.rememberUpdatedState(onScanned)
    val haptic = com.kachat.app.util.rememberHaptics()
    val hasScanned = remember { AtomicBoolean(false) }
    CameraScannerSheet(
        title = stringResource(R.string.scan_qr_code),
        deniedMessage = stringResource(R.string.qr_enable_camera_access),
        onDismiss = onDismiss,
        onResult = { result, close ->
            if (hasScanned.compareAndSet(false, true)) {
                haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                latestScanned(result.text)
                close()
            }
        },
    ) {
        ScannerPlate(stringResource(R.string.qr_point_camera))
    }
}

/**
 * Scans a KasSigner animated multi-frame QR sequence (a signed KSPT response, or anything else
 * chunked with [QrFrameChunker]) and reassembles it - iOS's `MultiFrameQRScannerView`, the same
 * sheet as [QrScannerSheet] titled "Scan Signed Transaction": "Point camera at the KasSigner
 * screen" until the first frame, then a dot per frame (the accent once seen) over "3 / 5 frames".
 * Unlike [QrScannerSheet], which takes the first code, this keeps scanning until every frame has
 * been seen, then hands the bytes over and slides down.
 */
@Composable
fun MultiFrameQrScannerSheet(
    isComplete: (ByteArray) -> Boolean,
    onComplete: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val latestComplete by androidx.compose.runtime.rememberUpdatedState(onComplete)
    val accumulator = remember { QrFrameChunker.Accumulator(isComplete) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var receivedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val done = remember { AtomicBoolean(false) }
    CameraScannerSheet(
        title = stringResource(R.string.scan_signed_transaction),
        deniedMessage = stringResource(R.string.qr_enable_camera_access_signed),
        onDismiss = onDismiss,
        onResult = { result, close ->
            if (done.get()) return@CameraScannerSheet
            val bytes = extractRawBytes(result) ?: return@CameraScannerSheet
            val complete = accumulator.addFrame(bytes)
            if (complete == null) {
                progress = accumulator.progress
                receivedIndices = accumulator.receivedFrameIndices
            } else if (done.compareAndSet(false, true)) {
                latestComplete(complete)
                close()
            }
        },
    ) {
        val (received, total) = progress ?: (0 to 0)
        if (total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                repeat(total) { i ->
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i in receivedIndices) KaspaTeal else Color.White.copy(alpha = 0.4f))
                    )
                }
            }
            ScannerPlate(stringResource(R.string.qr_frames_progress, received, total))
        } else {
            ScannerPlate(stringResource(R.string.qr_point_camera_kassigner))
        }
    }
}

/** The white headline on iOS's `.ultraThinMaterial` plate that sits under the scanning frame. */
@Composable
private fun ScannerPlate(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            // iOS's .ultraThinMaterial: a thin frosted plate over the camera.
            .background(if (LocalAppColors.current.isDark) Color(0x591C1C1E) else Color(0x4DFFFFFF))
            .padding(16.dp),
    )
}

/**
 * What [QrScannerSheet] and [MultiFrameQrScannerSheet] share - iOS's scanner sheets: an
 * [IosFullSheet] that swipes away, its inline bar ([title], Cancel leading) drawn over the camera
 * as iOS's transparent bar is, the 250 white frame, and [bottom] 50 above the bottom edge. Before
 * the camera may be used it says "Requesting camera access..."; refused, it shows "Camera Access
 * Required", [deniedMessage] and Open Settings, and looks again on coming back from Settings.
 * [onResult] gets every decode and `close`, which slides the sheet down.
 */
@Composable
private fun CameraScannerSheet(
    title: String,
    deniedMessage: String,
    onDismiss: () -> Unit,
    onResult: (Result, close: () -> Unit) -> Unit,
    bottom: @Composable () -> Unit,
) {
    val latestResult by androidx.compose.runtime.rememberUpdatedState(onResult)
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true) { close ->
        androidx.activity.compose.BackHandler(onBack = close)
        val context = LocalContext.current
        var authorized by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        var denied by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            authorized = granted
            denied = !granted
        }
        LaunchedEffect(Unit) { if (!authorized) launcher.launch(Manifest.permission.CAMERA) }
        // Back from Settings with access granted: the camera starts.
        val lifecycleOwner = LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !authorized &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                ) {
                    authorized = true
                    denied = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val colors = LocalAppColors.current
        Box(Modifier.fillMaxSize()) {
            when {
                authorized -> {
                    // Decodes arrive on the analyzer's thread; the sheet acts on them on the main one.
                    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
                    CameraQrScanner(modifier = Modifier.fillMaxSize()) { result ->
                        mainExecutor.execute { latestResult(result, close) }
                    }
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(250.dp).border(3.dp, Color.White, RoundedCornerShape(12.dp)))
                        Spacer(Modifier.weight(1f))
                        bottom()
                        Spacer(Modifier.height(50.dp))
                    }
                }
                denied -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(60.dp))
                    Text(stringResource(R.string.qr_camera_access_required), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        deniedMessage,
                        color = colors.textSecondary,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Button(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                    ) {
                        Text(stringResource(R.string.open_settings))
                    }
                }
                else -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary)
                    Text(stringResource(R.string.qr_requesting_camera_access), color = colors.textSecondary, fontSize = 17.sp)
                }
            }
            // iOS's inline navigation bar, drawn over the camera as its transparent bar is.
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                TextButton(onClick = close, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                    Text(stringResource(R.string.cancel), color = KaspaTeal, fontSize = 17.sp)
                }
                Text(
                    title,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
