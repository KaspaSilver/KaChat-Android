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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Hardware
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.border
import com.kachat.app.ui.hubTitle
import com.kachat.app.ui.theme.iosShadow
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

/**
 * One bubble in the Kaspa Websites grid - curated Kaspa sites opened in the in-app browser.
 * [name] is iOS's display name ("Kaspa Stream", not the domain); [icon] its SF Symbol's closest
 * Material glyph, unless [usesKaspaLogo] draws the Kaspa mark instead.
 */
data class KaspaApp(
    val name: String,
    val url: String,
    val icon: ImageVector = Icons.Outlined.Language,
    val usesKaspaLogo: Boolean = false,
)

/** Same curated list, names, icons and order as iOS's ProfileAppsView. */
val KASPA_APPS = listOf(
    KaspaApp("Kaspa.org", "https://kaspa.org", usesKaspaLogo = true),
    KaspaApp("Kaspa Stream", "https://kaspa.stream", Icons.Outlined.MonitorHeart),          // waveform.path.ecg
    KaspaApp("Kaspa Explorer", "https://explorer.kaspa.org", Icons.Outlined.Search),        // magnifyingglass
    KaspaApp("KasMap", "https://kasmap.org", Icons.Outlined.Map),                           // map
    KaspaApp("KasShi", "https://kasshi.io", Icons.Filled.SmartDisplay),                     // play.rectangle.fill
    KaspaApp("Kaspa News", "https://kaspa.news", Icons.Outlined.Newspaper),                 // newspaper
    KaspaApp("KasPlay", "https://kasplay.fun", Icons.Outlined.SportsEsports),               // gamecontroller
    KaspaApp("KasMart", "https://kasmart.org", Icons.Outlined.ShoppingCart),                // cart
    KaspaApp("KasMedia", "https://kasmedia.com", Icons.Outlined.Article),                   // doc.text.image
    KaspaApp("Kaspalytics", "https://www.kaspalytics.com", Icons.Outlined.Leaderboard),     // chart.bar
    KaspaApp("Kas-Smiths", "https://kas-smiths.org", Icons.Outlined.Hardware),              // hammer
    KaspaApp("Kaspa Core R&D", "https://t.me/kasparnd", Icons.Outlined.Science),            // atom
    // Free testnet-10 KAS for the app's testnet mode (Settings > Connection > Testnet uses TN10).
    KaspaApp("TN10 Faucet", "https://faucet-tn10.kaspanet.io", Icons.Filled.WaterDrop),     // drop.fill
)

/** The Kaspa Websites page - Kaspa Hub's tile and Profile > Apps open it (iOS ProfileAppsView). */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun KaspaAppsScreen(onBack: () -> Unit, onOpen: (KaspaApp) -> Unit) {
    val colors = LocalAppColors.current
    androidx.compose.material3.Scaffold(
        containerColor = colors.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            // iOS: `.navigationTitle(AppTab.apps.ecosystemTitle)`, a large title and nothing under it.
            MainPageHeader(title = com.kachat.app.ui.Screen.KaspaWebsites.hubTitle, onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(bottom = LocalDockInset.current),
        ) {
            KaspaAppsGrid(onOpen = onOpen)
        }
    }
}

/**
 * The bubble grid, laid out as iOS's `LazyVGrid(columns: [GridItem(.adaptive(minimum: 96),
 * spacing: 20)], spacing: 24)`: as many 96-wide columns as fit 20 apart (three on a phone), the
 * rows 24 apart, each bubble centred in its column.
 */
@Composable
fun KaspaAppsGrid(onOpen: (KaspaApp) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val perRow = maxOf(1, ((maxWidth + 20.dp) / (96.dp + 20.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            KASPA_APPS.chunked(perRow).forEach { rowApps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    rowApps.forEach { app ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            KaspaAppBubble(app, onClick = { onOpen(app) })
                        }
                    }
                    // A short last row keeps its columns where the full rows have them.
                    repeat(perRow - rowApps.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * One site: iOS's 68pt `.regularMaterial` circle with a 0.8 white hairline at 18% and a soft
 * shadow (black at 12%, radius 8, 4 down), the 28pt semibold symbol in the accent colour (the
 * Kaspa mark 34 square), and the name 8 below in caption semibold on one line.
 */
@Composable
private fun KaspaAppBubble(app: KaspaApp, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // The column's full width, so the name gets the whole grid column as on iOS (it was held
        // to the 68 bubble and cut "Kaspa Explorer" short).
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .iosShadow(34.dp, Color.Black.copy(alpha = 0.12f), 8.dp, 4.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (app.usesKaspaLogo) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_kaspa_logo),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
            } else {
                // A Material glyph is inset in its box; 32 draws it the size of a 28pt symbol.
                Icon(app.icon, null, tint = KaspaTeal, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            app.name,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
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
        // A large title, pushed inside the Profile tab (iOS ProfileHelpView,
        // `.navigationBarTitleDisplayMode(.large)`): the dock stays, like KaChat Bots.
        topBar = { MainPageHeader(title = androidx.compose.ui.res.stringResource(com.kachat.app.R.string.help), onBack = onBack, showStatus = false) },
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = LocalDockInset.current),
        ) {
            androidx.compose.material3.Surface(
                color = colors.surface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    HelpRow(
                        androidx.compose.material.icons.Icons.Default.AutoAwesome,
                        "Welcome Guide",
                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_welcome_body),
                        onWelcomeGuide,
                    )
                    androidx.compose.material3.HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 52.dp))
                    // .kachat's guide since 5.2 (iOS b000310).
                    HelpRow(
                        androidx.compose.material.icons.Icons.Default.Badge,
                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_names_help_title),
                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_names_help_body),
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // iOS helpRow: a 20pt icon in a 30pt frame, 14pt apart; subheadline semibold over caption.
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
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
