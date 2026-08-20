package com.kachat.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.PendingShare
import com.kachat.app.services.ShareIntake
import com.kachat.app.ui.theme.KaChatTheme
import com.kachat.app.ui.KaChatApp
import com.kachat.app.ui.screens.KaPostsDeepLink
import com.kachat.app.viewmodels.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single Activity — all navigation is handled in Compose via NavHost.
 *
 * Extends [AppCompatActivity] (rather than plain `FragmentActivity`, which it's a superset of)
 * because the per-app language API (`AppCompatDelegate.setApplicationLocales`, used by the
 * Language setting in Settings) relies on `AppCompatActivity`'s `attachBaseContext` override to
 * apply the locale override on API levels below 33.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var pendingContactId by mutableStateOf<String?>(null)
    private var pendingChannelName by mutableStateOf<String?>(null)
    private var pendingGroupId by mutableStateOf<String?>(null)

    // Android 13+ requires an explicit runtime grant before ANY notification (local or FCM push)
    // can be shown. Registered here (during construction, as required) and requested in onCreate.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * One-time nudge to exempt KaChat from battery optimization — the OS "Allow to run in the
     * background?" dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). Encrypted DM/group push is
     * delivered data-only so the app can decrypt it; a force-closed app under aggressive OEM
     * battery management may otherwise never wake to receive it. Only shown once, only after
     * notifications are enabled (so it doesn't stack on the notification-permission dialog), and
     * only if not already exempt.
     */
    private fun maybeRequestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        // Premature before the user has notifications on — wait until they do.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences("kachat_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_exemption_asked", false)) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("battery_exemption_asked", true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // A few OEMs don't implement the direct dialog — fall back to the battery-optimization list.
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones lock to portrait; tablets (sw>=600dp, see res/values-sw600dp/bools.xml) rotate
        // freely - mirroring iOS (iPhone portrait-only, iPad any orientation).
        requestedOrientation = if (resources.getBoolean(R.bool.lock_portrait)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        maybeRequestBatteryExemption()
        pendingContactId = intent?.getStringExtra(NotificationHelper.EXTRA_CONTACT_ID)
        pendingChannelName = intent?.getStringExtra(NotificationHelper.EXTRA_CHANNEL_NAME)
        pendingGroupId = intent?.getStringExtra(NotificationHelper.EXTRA_GROUP_ID)
        intent?.let(::handleKaPostDeepLink)
        intent?.let(::handleShareIntent)
        setContent {
            val walletViewModel: WalletViewModel = hiltViewModel()
            val darkModeEnabled by walletViewModel.darkModeEnabled.collectAsState()
            KaChatTheme(darkTheme = darkModeEnabled) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KaChatApp(
                        pendingContactId = pendingContactId,
                        onPendingContactHandled = { pendingContactId = null },
                        pendingChannelName = pendingChannelName,
                        onPendingChannelHandled = { pendingChannelName = null },
                        pendingGroupId = pendingGroupId,
                        onPendingGroupHandled = { pendingGroupId = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingContactId = intent.getStringExtra(NotificationHelper.EXTRA_CONTACT_ID)
        pendingChannelName = intent.getStringExtra(NotificationHelper.EXTRA_CHANNEL_NAME)
        pendingGroupId = intent.getStringExtra(NotificationHelper.EXTRA_GROUP_ID)
        handleKaPostDeepLink(intent)
        handleShareIntent(intent)
    }

    /**
     * System share sheet intake (ACTION_SEND / ACTION_SEND_MULTIPLE — text/plain and image MIME types).
     *
     * If the user picked a specific conversation on the share sheet (a direct-share shortcut
     * published by ShareShortcutsManager), the shortcut id — the contact's address — arrives in
     * [Intent.EXTRA_SHORTCUT_ID] and the share is targeted straight at that chat. Otherwise the
     * share is untargeted: the Chats list shows a "choose a chat" banner and whichever thread is
     * opened next consumes it (see ShareIntake).
     *
     * Image streams are copied into app cache immediately: the sender's content-URI read grant
     * is tied to this delivery and can't be relied on later when the user actually hits send.
     */
    private fun handleShareIntent(intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val type = intent.type ?: return
        val targetContactId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val imageStreams: List<android.net.Uri> = when {
            action == Intent.ACTION_SEND && type.startsWith("image/") ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java))
            action == Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    ?.filterNotNull() ?: emptyList()
            else -> emptyList()
        }
        if (text.isNullOrBlank() && imageStreams.isEmpty()) return
        lifecycleScope.launch {
            val cachedImages = withContext(Dispatchers.IO) { imageStreams.mapNotNull(::copySharedImageToCache) }
            if (text.isNullOrBlank() && cachedImages.isEmpty()) return@launch
            ShareIntake.pending.value = PendingShare(
                text = text?.takeIf { it.isNotBlank() },
                imageUris = cachedImages,
                targetContactId = targetContactId
            )
        }
    }

    /** Copies a shared image stream into app cache, returning a file:// URI readable at any later point (or null on failure). */
    private fun copySharedImageToCache(uri: android.net.Uri): android.net.Uri? {
        return try {
            val dir = File(cacheDir, "shared_images").apply { mkdirs() }
            // Stale copies from earlier shares are tiny compared to photo caches, but don't let
            // them accumulate forever either.
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }
                ?.forEach { it.delete() }
            val extension = when (contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            val file = File(dir, "share_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.$extension")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            android.net.Uri.fromFile(file)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to copy shared image", e)
            null
        }
    }

    /**
     * KaPosts share links land here: kachat://kapost/<txid> (the share-text form) and
     * https://kachat.duckdns.org/post/<txid> (universal-link form). The txid is handed to
     * [KaPostsDeepLink]; KaChatApp navigates to the KaPosts tab and the screen opens the
     * post's thread.
     */
    private fun handleKaPostDeepLink(intent: Intent) {
        // A KaPosts notification tap: deep-open the exact post/comment the notification was
        // about (EXTRA_KAPOST_TXID), falling back to just fronting the KaPosts tab ("").
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_KAPOSTS, false)) {
            KaPostsDeepLink.pendingPostTxId.value =
                intent.getStringExtra(NotificationHelper.EXTRA_KAPOST_TXID) ?: ""
            return
        }
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val txId = when {
            uri.scheme.equals("kachat", ignoreCase = true) &&
                uri.host.equals("kapost", ignoreCase = true) -> uri.lastPathSegment
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("kachat.duckdns.org", ignoreCase = true) &&
                uri.pathSegments.firstOrNull() == "post" -> uri.pathSegments.getOrNull(1)
            else -> null
        }
        if (!txId.isNullOrBlank()) {
            KaPostsDeepLink.pendingPostTxId.value = txId
        }
    }
}
