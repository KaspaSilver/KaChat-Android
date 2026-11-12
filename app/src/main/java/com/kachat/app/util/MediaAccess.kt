package com.kachat.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kachat.app.R
import com.kachat.app.ui.theme.IosAlertDialog
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * Camera and microphone access for the chat composers (1:1 and group), iOS ca013fc8
 * `MediaAccess` (CameraCaptureView.swift). The first use asks with the system prompt. Once access
 * is off, the composers show [MediaAccessAlert], which explains it and opens the app's settings,
 * instead of a camera that never opens or a mic button that does nothing. Someone who has just
 * tapped Don't Allow isn't told again: the permission result's denial just closes.
 *
 * iOS's prompt reasons (NSCameraUsageDescription / NSMicrophoneUsageDescription) have no Android
 * counterpart - the system dialog carries no app text - so there is no rationale UI here, as iOS
 * shows none before its prompt.
 */
enum class MediaAccess(
    val permission: String,
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
) {
    CAMERA(
        Manifest.permission.CAMERA,
        R.string.qr_camera_access_required,
        R.string.allow_camera_access_in_settings_to_take_photos,
    ),
    MICROPHONE(
        Manifest.permission.RECORD_AUDIO,
        R.string.microphone_access_required,
        R.string.allow_microphone_access_in_settings_to_record_voice,
    );

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Access was turned off for good: asking again shows nothing, only Settings helps (iOS
     * `isDenied`). Android says so as "not granted, asked before, and no rationale to show" -
     * after one Don't Allow the system still asks again (the rationale is due), after the second
     * it doesn't.
     */
    fun isDenied(context: Context): Boolean {
        if (isGranted(context)) return false
        if (!wasAsked(context)) return false
        val activity = context.findFragmentActivity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /**
     * Asks through [launcher] (whose result goes on as the caller's own), or calls [onDenied]
     * when access is off for good, for the caller to show [MediaAccessAlert]. Granted access is
     * the caller's to check first.
     */
    fun request(context: Context, launcher: ActivityResultLauncher<String>, onDenied: (MediaAccess) -> Unit) {
        if (isDenied(context)) {
            onDenied(this)
            return
        }
        markAsked(context)
        launcher.launch(permission)
    }

    private fun wasAsked(context: Context): Boolean =
        prefs(context).getBoolean(name, false)

    private fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(name, true).apply()
    }

    companion object {
        private const val PREFS = "kachat_media_access_asked"

        private fun prefs(context: Context) =
            (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The app's own page in the system settings, where camera and microphone are turned on. */
        fun openAppSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * "Camera Access Required" / "Microphone Access Required" with Open Settings and Not Now, while
 * [denied] is set (iOS `mediaAccessAlert`).
 */
@Composable
fun MediaAccessAlert(denied: MediaAccess?, onDismiss: () -> Unit) {
    val access = denied ?: return
    val context = LocalContext.current
    IosAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(access.titleRes), color = LocalAppColors.current.textPrimary) },
        text = { Text(stringResource(access.messageRes), color = LocalAppColors.current.textPrimary) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                MediaAccess.openAppSettings(context)
            }) { Text(stringResource(R.string.open_settings), color = KaspaTeal) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.not_now), color = KaspaTeal, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
