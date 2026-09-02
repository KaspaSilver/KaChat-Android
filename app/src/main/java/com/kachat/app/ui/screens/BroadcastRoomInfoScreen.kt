package com.kachat.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.BroadcastViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything about one broadcast room that is not the messages: what it is, what is in it, how to
 * share it, who you have hidden, and which indexer it reads from.
 *
 * Reached by tapping the `#name` title in the room. It replaced two toolbar glyphs (share and
 * hidden users) that had no room to say what they were, and gave the per-room indexer somewhere
 * to live that is not the app-wide Connection Settings. Mirrors iOS's BroadcastRoomInfoView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastRoomInfoScreen(
    channelName: String,
    onBack: () -> Unit,
    /** Pushes the room's hidden-users list, the way iOS pushes HiddenBroadcastSendersView. */
    onOpenHiddenUsers: () -> Unit,
    broadcastViewModel: BroadcastViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val normalized = remember(channelName) { channelName.trim().lowercase() }

    val messages by broadcastViewModel.getMessages(normalized).collectAsState(initial = emptyList())
    val joinedChannels by broadcastViewModel.joinedChannels.collectAsState()
    val hiddenSenders by broadcastViewModel.hiddenSenders.collectAsState()
    val appWideIndexer by broadcastViewModel.appWideBroadcastIndexer.collectAsState()
    val storedOverride by broadcastViewModel.indexerOverrideFor(normalized)
        .collectAsState(initial = "")

    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()
    var indexerText by remember { mutableStateOf("") }
    // Seeded once the stored value arrives, never on every emission - retyping would fight the
    // user mid-edit.
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(storedOverride) {
        if (!seeded) { indexerText = storedOverride; seeded = true }
    }

    val isCurated = normalized in FeaturedBroadcastChannels.INDEXED_NAMES
    val channel = joinedChannels.firstOrNull { it.channelName == normalized }
    val participants = remember(messages) { messages.map { it.senderAddress }.distinct().size }
    val hiddenHere = remember(hiddenSenders, normalized) {
        com.kachat.app.repository.BroadcastRepository.hiddenAddressesIn(normalized, hiddenSenders).size
    }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Room Info", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        saveIndexer(broadcastViewModel, normalized, indexerText, storedOverride)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "#$normalized",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isCurated) {
                    "A curated room, always in your list. Anyone running KaChat can post to it."
                } else {
                    "A room you added. Anyone who knows the name can post to it."
                },
                color = colors.textSecondary,
                fontSize = 12.sp,
            )

            InfoCard(title = "On this device") {
                FeaturedBroadcastChannels.languageDisplayName(normalized)?.let { InfoRow("Language", it) }
                InfoRow("Kind", if (isCurated) "Popular" else "Added by you")
                channel?.joinedAt?.let { InfoRow("Joined", dateFormat.format(Date(it))) }
                InfoRow("Messages", messages.size.toString())
                InfoRow("People who posted", participants.toString())
                messages.maxOfOrNull { it.blockTimestamp }?.let {
                    InfoRow("Latest", dateFormat.format(Date(it)))
                }
                messages.minOfOrNull { it.blockTimestamp }?.let {
                    InfoRow("Oldest held", dateFormat.format(Date(it)))
                }
            }

            ActionSheetRow(
                icon = Icons.Default.Share,
                title = "Share this room",
                subtitle = "Sends a link that opens it in KaChat, and a web link for anyone without it.",
            ) {
                val text = "Join #$normalized on KaChat: ${com.kachat.app.ui.screens.KaChatLink.broadcastUrl(normalized)}"
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }

            ActionSheetRow(
                icon = Icons.Default.VisibilityOff,
                title = if (hiddenHere == 0) "Hidden users" else "Hidden users ($hiddenHere)",
                subtitle = "Per room: someone hidden here still shows in every other room.",
                onClick = onOpenHiddenUsers,
            )

            InfoCard(title = "Indexer for this room") {
                OutlinedTextField(
                    value = indexerText,
                    onValueChange = { indexerText = it },
                    placeholder = { Text(appWideIndexer, color = colors.textSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = colors.textSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // The honest explanation of why this is per room at all.
                    "A broadcast lives on the Kaspa blockDAG, so any indexer watching the same " +
                        "network serves the same room. Point this one wherever you like - your own, " +
                        "or someone else's - without changing the indexer every other room uses. " +
                        "Leave it blank to follow $appWideIndexer.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (indexerText.isNotBlank()) {
                        TextButton(onClick = {
                            indexerText = ""
                            broadcastViewModel.setIndexerOverride(normalized, "")
                        }) {
                            Text("Use the app's indexer", color = colors.textSecondary)
                        }
                    }
                    Button(
                        onClick = { saveIndexer(broadcastViewModel, normalized, indexerText, storedOverride) },
                        enabled = indexerText.trim() != storedOverride.trim(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KaspaTeal,
                            contentColor = androidx.compose.ui.graphics.Color.Black,
                        ),
                    ) {
                        Text(stringResourceSave(), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun stringResourceSave(): String =
    androidx.compose.ui.res.stringResource(R.string.save)

private fun saveIndexer(
    viewModel: BroadcastViewModel,
    channel: String,
    typed: String,
    stored: String,
) {
    if (typed.trim() == stored.trim()) return
    viewModel.setIndexerOverride(channel, typed)
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(14.dp),
    ) {
        Text(title, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Who you have hidden in ONE broadcast room. Reached from Room Info; mirrors iOS's pushed
 * `HiddenBroadcastSendersView`.
 *
 * Its own screen rather than a sheet inside Room Info: unhiding is a list you work through, and
 * a sheet stacked on a sheet gives it no room and no title of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastHiddenUsersScreen(
    channelName: String,
    onBack: () -> Unit,
    broadcastViewModel: BroadcastViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val normalized = remember(channelName) { channelName.trim().lowercase() }
    val hiddenSenders by broadcastViewModel.hiddenSenders.collectAsState()
    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()

    // hiddenAddressesIn returns a Set; sorted into a stable list so rows do not reshuffle when
    // one is unhidden.
    val rows = remember(hiddenSenders, normalized) {
        com.kachat.app.repository.BroadcastRepository
            .hiddenAddressesIn(normalized, hiddenSenders)
            .sorted()
    }
    LaunchedEffect(rows) { rows.forEach { broadcastViewModel.ensureSenderProfileFetched(it) } }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Hidden in #$normalized", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nobody is hidden here. Hide someone from their avatar in the room.",
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(rows, key = { it }) { address ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        contactAliases[address] ?: senderKnsNames[address] ?: address.takeLast(10),
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { broadcastViewModel.unhideSender(address, normalized) }) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.unhide),
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
