package com.kachat.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "What's new in 4.0" dock wizard - shown ONCE per install (any dismissal is permanent, see
 * AppSettingsRepository.KEY_DOCK_WIZARD_DISMISSED). Four pages with small animated demos of
 * the Chats-slot cycle behavior, mirroring iOS's DockWizardView. (The old "+ More" dock entry
 * is gone - customization now lives in Settings > Customization > Customize Dock.)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DockWizardDialog(onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "What's new in 4.0",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                when (page) {
                    0 -> WizardPage(
                        title = "Meet KaPosts",
                        body = "A social feed built on Kaspa - post, follow, and discover, fully on-chain. It lives in your dock now.",
                    ) { StaticIconDemo(Icons.Default.EditNote) }
                    1 -> WizardPage(
                        title = "Tap Chats to cycle",
                        body = "When your dock is full, tapping the Chats tab cycles through Chats, KaPosts and Broadcasts.",
                    ) { CycleDemo() }
                    2 -> WizardPage(
                        title = "Hold to jump",
                        body = "Hold the Chats tab and a menu rises up - slide onto the page you want and let go.",
                    ) { HoldSlideDemo() }
                    3 -> WizardPage(
                        title = "Make it yours",
                        body = "Choose which tabs show and reorder them in Settings > Customization > Customize Dock.",
                    ) { StaticIconDemo(Icons.Default.Tune) }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (pagerState.currentPage == index) KaspaTeal else colors.surfaceVariant),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (pagerState.currentPage < 3) {
                Button(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text("Next", color = Color.White, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) {
                    Text("Skip", color = colors.textSecondary)
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text("Get Started", color = Color.White, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) {
                    Text("Don't show again", color = colors.textSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun WizardPage(title: String, body: String, demo: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.height(96.dp), contentAlignment = Alignment.Center) { demo() }
        Spacer(modifier = Modifier.height(12.dp))
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            body,
            color = colors.textSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun StaticIconDemo(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(KaspaTeal.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(36.dp))
    }
}

/** The Chats slot morphing through its cycle: chats -> kaposts -> broadcasts, with a tap pulse. */
@Composable
private fun CycleDemo() {
    val icons = listOf(Icons.Default.Forum, Icons.Default.EditNote, Icons.Default.Sensors)
    var index by remember { mutableIntStateOf(0) }
    var pulsing by remember { androidx.compose.runtime.mutableStateOf(false) }
    val scale by animateFloatAsState(if (pulsing) 0.82f else 1f, tween(180), label = "pulse")
    LaunchedEffect(Unit) {
        while (true) {
            delay(1100)
            pulsing = true
            delay(180)
            pulsing = false
            index = (index + 1) % icons.size
        }
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(KaspaTeal.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icons[index], contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(36.dp))
    }
}

/** The hold menu: three options with a highlight sweeping across, finger dot beneath. */
@Composable
private fun HoldSlideDemo() {
    val colors = LocalAppColors.current
    val icons = listOf(Icons.Default.Forum, Icons.Default.EditNote, Icons.Default.Sensors)
    var highlight by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(900)
            highlight = (highlight + 1) % icons.size
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(colors.background)
                .padding(6.dp),
        ) {
            icons.forEachIndexed { i, icon ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (i == highlight) KaspaTeal.copy(alpha = 0.22f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon, contentDescription = null,
                        tint = if (i == highlight) KaspaTeal else colors.textSecondary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Icon(
            Icons.Default.TouchApp,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier
                .size(22.dp)
                .alpha(0.9f),
        )
    }
}
