package com.kachat.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.services.NotificationHelper
import com.kachat.app.ui.theme.KaChatTheme
import com.kachat.app.ui.KaChatApp
import com.kachat.app.ui.screens.KaPostsDeepLink
import com.kachat.app.viewmodels.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint

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
        pendingContactId = intent?.getStringExtra(NotificationHelper.EXTRA_CONTACT_ID)
        pendingChannelName = intent?.getStringExtra(NotificationHelper.EXTRA_CHANNEL_NAME)
        pendingGroupId = intent?.getStringExtra(NotificationHelper.EXTRA_GROUP_ID)
        intent?.let(::handleKaPostDeepLink)
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
    }

    /**
     * KaPosts share links land here: kachat://kapost/<txid> (the share-text form) and
     * https://kachat.duckdns.org/post/<txid> (universal-link form). The txid is handed to
     * [KaPostsDeepLink]; KaChatApp navigates to the KaPosts tab and the screen opens the
     * post's thread.
     */
    private fun handleKaPostDeepLink(intent: Intent) {
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
