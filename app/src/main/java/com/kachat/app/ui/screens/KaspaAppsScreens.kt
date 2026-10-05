package com.kachat.app.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/** One bubble in the Profile > Apps grid - curated Kaspa sites opened in the in-app browser. */
data class KaspaApp(val name: String, val url: String, val icon: ImageVector = Icons.Default.Language)

/** Same curated list as iOS's ProfileAppsView. */
val KASPA_APPS = listOf(
    KaspaApp("kaspa.org", "https://kaspa.org"),
    KaspaApp("kaspa.stream", "https://kaspa.stream"),
    KaspaApp("Kaspa explorer", "https://explorer.kaspa.org"),
    KaspaApp("kasmap.org", "https://kasmap.org"),
    KaspaApp("KasShi", "https://kasshi.io", Icons.Default.PlayCircleOutline),
    KaspaApp("kaspa.news", "https://kaspa.news"),
    KaspaApp("kasplay.fun", "https://kasplay.fun"),
    KaspaApp("kasmart.org", "https://kasmart.org"),
    KaspaApp("kasmedia.com", "https://kasmedia.com"),
    KaspaApp("Kaspalytics", "https://www.kaspalytics.com"),
    KaspaApp("Kas-Smiths", "https://kas-smiths.org"),
    KaspaApp("Kaspa Core R&D", "https://t.me/kasparnd"),
    // Free testnet-10 KAS for the app's testnet mode (Settings > Connection > Testnet uses TN10).
    KaspaApp("TN10 Faucet", "https://faucet-tn10.kaspanet.io", Icons.Default.WaterDrop),
)

/** Full Apps page - Profile > Apps navigates here (its own screen, matching iOS's ProfileAppsView). */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun KaspaAppsScreen(onBack: () -> Unit, onOpen: (KaspaApp) -> Unit) {
    val colors = LocalAppColors.current
    androidx.compose.material3.Scaffold(
        containerColor = colors.background,
        topBar = {
            MainPageHeader(title = "Apps", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Curated Kaspa sites - everything opens right inside KaChat.",
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            KaspaAppsGrid(onOpen = onOpen)
        }
    }
}

/** Bubble grid (3 per row), matching iOS's Apps section in Profile. */
@Composable
fun KaspaAppsGrid(onOpen: (KaspaApp) -> Unit) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        KASPA_APPS.chunked(3).forEach { rowApps ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                rowApps.forEach { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onOpen(app) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(KaspaTeal.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(app.icon, null, tint = KaspaTeal, modifier = Modifier.size(26.dp))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            app.name,
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen in-app browser for the Apps bubbles - the only way out is the X top-left,
 * matching iOS's InAppBrowserScreen. Plain WebView with JS enabled; navigation stays inside.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserScreen(url: String, title: String, notice: String? = null, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = KaspaTeal)
            }
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        // Optional strip under the top bar (Claim Testnet Kaspa: "your address is copied",
        // iOS 2a81767).
        if (notice != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KaspaTeal.copy(alpha = 0.15f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(notice, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        androidx.compose.material3.HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            },
            // Closing the browser frees the page (its renderer, timers and media) instead of
            // leaving it to the garbage collector.
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
        )
    }
}

/**
 * Help hub - Profile > Help, matching iOS's ProfileHelpView: one place holding the Welcome
 * Guide, the KNS Profile Setup Guide, and the Dock Guide (the 4.0 wizard, replayable here).
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    onWelcomeGuide: () -> Unit,
    onKnsGuide: () -> Unit,
) {
    val colors = LocalAppColors.current
    androidx.compose.material3.Scaffold(
        containerColor = colors.background,
        topBar = {
            androidx.compose.material3.CenterAlignedTopAppBar(
                title = { Text("Help", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "Back", tint = KaspaTeal)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            androidx.compose.material3.Surface(
                color = colors.surface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    HelpRow(
                        androidx.compose.material.icons.Icons.Default.WavingHand,
                        "Welcome Guide",
                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_welcome_body),
                        onWelcomeGuide,
                    )
                    androidx.compose.material3.HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 52.dp))
                    // .kachat's guide since 5.2 (iOS b000310).
                    HelpRow(
                        androidx.compose.material.icons.Icons.Default.AlternateEmail,
                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_help_title),
                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_help_body),
                        onKnsGuide,
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpRow(icon: ImageVector, label: String, subtitle: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
    ) {
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.padding(start = 16.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 13.sp)
        }
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.textSecondary, modifier = Modifier.size(24.dp))
    }
}

/**
 * "Claim Testnet Kaspa", above the Chatting card on Profile, testnet only (iOS 182ae68, 2a81767).
 * The official TN10 faucet sits behind a Cloudflare check, so it can't be claimed in the
 * background: the button copies the chatting address in its `kaspatest:` form and opens the
 * faucet in the in-app browser, with a strip saying it's copied. The faucet allows one claim a
 * day, so once the chatting balance goes up after a visit the button locks for 24 hours (kept
 * per address, with a countdown). No rise leaves it free to try again.
 */
@Composable
fun TestnetFaucetClaimButton(
    address: String,
    balanceSompi: () -> Long,
    refreshBalance: suspend () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("kachat_tn10_faucet", android.content.Context.MODE_PRIVATE) }
    val key = "claimed_${address.lowercase()}"
    var claimedAt by remember(key) { mutableStateOf(prefs.getLong(key, 0L).takeIf { it > 0L }) }
    var showFaucet by remember { mutableStateOf(false) }
    var balanceBefore by remember { mutableStateOf<Long?>(null) }
    var checkingClaim by remember { mutableStateOf(false) }
    // TimelineView(.periodic(by: 60)): re-read the clock every minute for the countdown.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L)
        }
    }
    val until = claimedAt?.let { it + FAUCET_LOCK_MS }?.takeIf { it > now }
    val locked = until != null

    /** After the faucet closes: the payment usually lands within seconds, so watch the chatting
     *  balance for up to a minute. A rise means the claim went through - lock for 24 hours. */
    suspend fun checkForClaim() {
        val before = balanceBefore ?: return
        checkingClaim = true
        try {
            repeat(12) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(5_000L)
                runCatching { refreshBalance() }
                if (balanceSompi() > before) {
                    val at = System.currentTimeMillis()
                    claimedAt = at
                    now = at
                    prefs.edit().putLong(key, at).apply()
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    return
                }
            }
        } finally {
            checkingClaim = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(KaspaTeal.copy(alpha = if (locked) 0.06f else 0.15f))
            .clickable(enabled = !locked && !checkingClaim) {
                // The faucet's address field takes the TN10 form; the wallet already shows it
                // that way on testnet - this makes sure of it.
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.kachat.app.util.KaspaNetwork.reencode(address, "kaspatest")))
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                balanceBefore = balanceSompi()
                showFaucet = true
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                checkingClaim -> Icons.Default.HourglassTop
                locked -> Icons.Default.CheckCircle
                else -> Icons.Default.WaterDrop
            },
            contentDescription = null,
            tint = if (locked) colors.textSecondary else KaspaTeal,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.faucet_claim_title),
                color = if (locked) colors.textSecondary else colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    checkingClaim -> stringResource(R.string.faucet_waiting)
                    until != null -> stringResource(R.string.faucet_claimed_again_in, faucetRemaining(until - now))
                    else -> stringResource(R.string.faucet_claim_subtitle)
                },
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        if (!locked && !checkingClaim) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        }
    }

    if (showFaucet) {
        val close = {
            showFaucet = false
            scope.launch { checkForClaim() }
            Unit
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = close,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            InAppBrowserScreen(
                url = FAUCET_URL,
                title = "faucet-tn10.kaspanet.io",
                notice = stringResource(R.string.faucet_address_copied_notice),
                onClose = close,
            )
        }
    }
}

private const val FAUCET_URL = "https://faucet-tn10.kaspanet.io"
private const val FAUCET_LOCK_MS = 24L * 60 * 60 * 1000

/** "5h 12m" / "37m" - iOS's abbreviated DateComponentsFormatter, at least a minute. */
private fun faucetRemaining(ms: Long): String {
    val minutes = maxOf(1L, ms / 60_000L)
    return if (ms >= 3_600_000L) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
