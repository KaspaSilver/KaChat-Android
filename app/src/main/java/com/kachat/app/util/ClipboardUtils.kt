package com.kachat.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.kachat.app.R

/**
 * Copies sensitive text (a private key hex) to the system clipboard and auto-wipes it 30 seconds
 * later, whether or not the app is still open (see [wipeSensitiveClip]). Mirrors the iOS 30-second
 * clipboard clear for private-key material.
 *
 * The recovery phrase goes through here too (iOS f9beba1): copying it is how it reaches a
 * password manager, and the same sensitive marking and 30-second wipe apply.
 */
/**
 * The one standardized confirmation toast for copying an address to the clipboard, shared by
 * every address-copy site in the app (iOS shows the identical text): "Address kaspa:qz3x...m2aj...8f2k copied".
 *
 * The shortened form is three segments of the payload - the first 4 characters, 4 characters from
 * the exact middle, and the last 4 - joined with "...", keeping the network prefix. Three segments
 * (rather than the ends-only [KaspaAddress.shortDisplay]) make lookalike-address swaps harder to
 * miss when the user glances at the confirmation. Short or abnormal values (no prefix, tiny
 * payload - e.g. a foreign chain's swap deposit address) fall back to the plain string.
 */
fun showAddressCopiedToast(context: Context, address: String, haptic: Boolean = true) {
    // iOS plays Haptics.success() with nearly every address copy; the few that don't (a group
    // member's or a public room sender's address) pass false.
    if (haptic) {
        var c: Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        (c as? android.app.Activity)?.window?.decorView?.playHaptic(IosHaptic.SUCCESS)
    }
    com.kachat.app.ui.screens.IosToasts.show(context.getString(R.string.address_copied_short, addressCopiedDisplay(address)))
}

/**
 * The three-segment shortened form used by [showAddressCopiedToast]; see its doc comment. iOS's
 * `addressToastShortened`: trimmed, the prefix through its colon kept (none without one), and the
 * payload shortened only when it is at least 24 characters, so the segments cannot overlap.
 */
internal fun addressCopiedDisplay(address: String): String {
    val trimmed = address.trim()
    val colon = trimmed.indexOf(':')
    val prefix = if (colon >= 0) trimmed.substring(0, colon + 1) else ""
    val body = if (colon >= 0) trimmed.substring(colon + 1) else trimmed
    if (body.length < 24) return trimmed
    val midStart = body.length / 2 - 2
    return "$prefix${body.take(4)}...${body.substring(midStart, midStart + 4)}...${body.takeLast(4)}"
}

fun copyPrivateKeyWithAutoWipe(context: Context, value: String, label: String = "private key") {
    val appContext = context.applicationContext
    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(SENSITIVE_CLIP_LABEL_PREFIX + label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Marks the clip sensitive on Android 13+: the system clipboard preview redacts the key
        // instead of flashing it on screen, and clipboard-history/sync surfaces treat it as
        // secret. No-op below API 33.
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    // Scheduled too, so the wipe survives the app being closed; the first of the two to run wins.
    val workManager = androidx.work.WorkManager.getInstance(appContext)
    workManager.enqueueUniqueWork(
        CLIPBOARD_WIPE_WORK,
        androidx.work.ExistingWorkPolicy.REPLACE,
        androidx.work.OneTimeWorkRequestBuilder<ClipboardWipeWorker>()
            .setInitialDelay(CLIPBOARD_WIPE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build(),
    )
    Handler(Looper.getMainLooper()).postDelayed({
        wipeSensitiveClip(appContext)
        workManager.cancelUniqueWork(CLIPBOARD_WIPE_WORK)
    }, CLIPBOARD_WIPE_DELAY_MS)
}

private const val SENSITIVE_CLIP_LABEL_PREFIX = "KaChat secret: "
private const val CLIPBOARD_WIPE_WORK = "kachat_clipboard_wipe"
private const val CLIPBOARD_WIPE_DELAY_MS = 30_000L

/**
 * Clears the clipboard unless it visibly holds something the user copied since. Android hides the
 * clipboard from an app in the background, and reading it to compare is why the wipe used to do
 * nothing after the user switched to a password manager: there, "can't tell" clears it. Only the
 * clip's label is compared, so the secret itself is never kept anywhere to compare against.
 */
internal fun wipeSensitiveClip(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val label = runCatching { clipboard.primaryClipDescription }.getOrNull()?.label?.toString()
    if (!shouldWipeClip(label)) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        clipboard.clearPrimaryClip()
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}

/** [visibleLabel] is the clip's label, or null when Android won't show it to this app right now. */
internal fun shouldWipeClip(visibleLabel: String?): Boolean =
    visibleLabel == null || visibleLabel.startsWith(SENSITIVE_CLIP_LABEL_PREFIX)

/** The wipe, for when the app was closed before its 30 seconds were up. */
class ClipboardWipeWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.Worker(context, params) {
    override fun doWork(): Result {
        wipeSensitiveClip(applicationContext)
        return Result.success()
    }
}
