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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

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
    // One analysis thread per scanner, stopped when the scanner goes: the analyzer is cleared
    // first (on the main thread, so CameraX hands it no further frame), then the thread shuts
    // down after any frame it is still decoding. A scanner that closes before the camera
    // provider answers never sets the analyzer at all.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysisRef = remember { arrayOfNulls<ImageAnalysis>(1) }
    val disposed = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposed[0] = true
            analysisRef[0]?.clearAnalyzer()
            analysisExecutor.shutdown()
        }
    }

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
                if (disposed[0]) return@addListener
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
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    decodeQrCode(imageProxy, onResult)
                }
                analysisRef[0] = analysis
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

// Restricting to QR (this app never scans anything else) both speeds up MultiFormatReader's
// attempt per frame and avoids it occasionally matching a stray non-QR pattern in the frame.
// TRY_HARDER searches the frame more thoroughly (a small or angled code), and ALSO_INVERTED also
// reads a light-on-dark code, as iOS's scanner does.
private val qrOnlyHints = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.ALSO_INVERTED to true,
)

private fun decodeQrCode(imageProxy: ImageProxy, onResult: (Result) -> Unit) {
    try {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        decodeQrLuminance(data, imageProxy.width, imageProxy.height, plane.rowStride)?.let(onResult)
    } catch (e: Exception) {
        // Decode failure for this frame - ignore and try the next one.
    } finally {
        imageProxy.close()
    }
}

/**
 * Decodes a QR code from a camera frame's luminance (Y) plane, or null when the frame holds none.
 *
 * [rowStride] is how many bytes each row of [data] takes, which on many phones is MORE than
 * [width] (rows are padded for alignment). Reading the plane as if rows were [width] long skews
 * every row after the first, and nothing in the frame ever decodes - the camera shows, the code
 * is never read.
 */
internal fun decodeQrLuminance(data: ByteArray, width: Int, height: Int, rowStride: Int): Result? {
    val stride = rowStride.coerceAtLeast(width)
    // The last row may stop at [width] rather than run the full stride.
    val rows = minOf(height, (data.size - width) / stride + 1)
    if (width <= 0 || rows <= 0) return null
    val source = PlanarYUVLuminanceSource(data, stride, rows, 0, 0, width, rows, false)
    return try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), qrOnlyHints)
    } catch (e: NotFoundException) {
        // HybridBinarizer's local-contrast threshold can miss a code under uneven lighting
        // (e.g. reading a phone/device screen's own backlight glare) that a simpler global
        // threshold catches - worth one more attempt on the same frame before giving up on it.
        try {
            MultiFormatReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), qrOnlyHints)
        } catch (e: NotFoundException) {
            null
        }
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
            // iOS 26's inline navigation bar, drawn over the camera as its transparent bar is:
            // the title in headline, Cancel on a glass capsule 16 in.
            SheetNavBar(
                title = title,
                leading = { SheetBarButton(stringResource(R.string.cancel), onClick = close) },
            )
        }
    }
}
