package com.kachat.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.kachat.app.R

/**
 * Copies sensitive text (a private key hex) to the system clipboard and auto-wipes it 30 seconds
 * later — but only if the clipboard still holds that exact value (i.e. the user hasn't copied
 * something else in the meantime). A main-looper [Handler] is used rather than a composition-scoped
 * coroutine so the wipe still fires after the user navigates away from the screen that copied it.
 * Mirrors the iOS 30-second clipboard clear for private-key material.
 *
 * Seed phrases are deliberately NOT copyable anywhere and must never be passed to this helper —
 * they can only be transcribed by hand.
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
fun showAddressCopiedToast(context: Context, address: String) {
    Toast.makeText(
        context,
        context.getString(R.string.address_copied_short, addressCopiedDisplay(address)),
        Toast.LENGTH_SHORT
    ).show()
}

/** The three-segment shortened form used by [showAddressCopiedToast]; see its doc comment. */
internal fun addressCopiedDisplay(address: String): String {
    val parts = address.split(":", limit = 2)
    if (parts.size != 2) return address
    val (prefix, body) = parts
    if (body.length < 16) return address
    val midStart = body.length / 2 - 2
    return "$prefix:${body.take(4)}...${body.substring(midStart, midStart + 4)}...${body.takeLast(4)}"
}

fun copyPrivateKeyWithAutoWipe(context: Context, value: String, label: String = "private key") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Marks the clip sensitive on Android 13+: the system clipboard preview redacts the key
        // instead of flashing it on screen, and clipboard-history/sync surfaces treat it as
        // secret. No-op below API 33.
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (current == value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }, 30_000L)
}
