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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
fun InAppBrowserScreen(url: String, title: String, onClose: () -> Unit) {
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
    var showDockGuide by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.Scaffold(
        containerColor = colors.background,
        topBar = {
            androidx.compose.material3.CenterAlignedTopAppBar(
                title = { Text("Help", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = KaspaTeal)
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
                    HelpRow(androidx.compose.material.icons.Icons.Default.WavingHand, "Welcome Guide", onWelcomeGuide)
                    androidx.compose.material3.HorizontalDivider(color = colors.surfaceVariant)
                    HelpRow(androidx.compose.material.icons.Icons.Default.Badge, "KNS Profile Setup Guide", onKnsGuide)
                    androidx.compose.material3.HorizontalDivider(color = colors.surfaceVariant)
                    HelpRow(androidx.compose.material.icons.Icons.Default.Tune, "Dock Guide") { showDockGuide = true }
                }
            }
        }
    }
    if (showDockGuide) {
        DockWizardDialog(onDismiss = { showDockGuide = false })
    }
}

@Composable
private fun HelpRow(icon: ImageVector, label: String, onClick: () -> Unit) {
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
        Text(label, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
