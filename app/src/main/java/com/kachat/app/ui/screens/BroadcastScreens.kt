package com.kachat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.models.BroadcastRetention
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.ChatRepository
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChatTimeFormat
import com.kachat.app.util.MessageReply
import com.kachat.app.util.TextLinkify
import com.kachat.app.util.VoiceMessage
import com.kachat.app.viewmodels.BroadcastViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BroadcastListScreen(
    navController: NavController,
    onBack: () -> Unit,
    broadcastViewModel: BroadcastViewModel = hiltViewModel()
) {
    val channels by broadcastViewModel.joinedChannels.collectAsState()
    val showKnsAvatarsEnabled by broadcastViewModel.showKnsAvatarsEnabled.collectAsState()
    val hiddenSenders by broadcastViewModel.hiddenSenders.collectAsState()
    val joinState by broadcastViewModel.joinChannelState.collectAsState()
    var showJoinDialog by remember { mutableStateOf(false) }
    var showBroadcastSettingsDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
    var channelToLeave by remember { mutableStateOf<String?>(null) }
    var retentionSettingsChannelName by remember { mutableStateOf<String?>(null) }
    // Collapsed by default: eleven language rooms would bury the two Popular rooms and the
    // user's own channels under a wall of list.
    var languagesExpanded by remember { mutableStateOf(false) }
    val languagesRotation by animateFloatAsState(
        targetValue = if (languagesExpanded) 0f else -90f,
        label = "languagesChevron"
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(joinState.status) {
        if (joinState.status == BroadcastViewModel.JoinChannelStatus.SUCCESS) {
            showJoinDialog = false
        }
    }

    // Don't leave the user stuck on a tab that just got hidden.

    Scaffold(
        containerColor = LocalAppColors.current.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.broadcasts), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 26.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                        }
                    },
                    actions = {
                        // Matches iOS BroadcastListView's toolbar: gear only — the join/create
                        // "+" lives on the "Your Channels" section header row below instead.
                        IconButton(onClick = { showBroadcastSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, "Broadcast Settings", tint = KaspaTeal)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
                )
                // Total balance under the title, same header anatomy as the other main pages
                // (iOS BroadcastListView's centered BalanceToolbarLabel).
                BalanceTopBarLabel(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp)
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 4.0 (matches iOS BroadcastListView.combinedList): ONE page, no tabs, two sections —
            // the curated Popular rooms pinned on top (permanent, auto-joined by the VM's init,
            // bell-only), then everything the user joined under "Your Channels", whose header
            // row carries the "+" join/create entry point. Item keys use ':' prefixes because
            // a colon can never appear in a channel name (MessageProtocol.isValidChannelName),
            // so a user-joined channel can't collide with a header/popular key.
            // Every curated room (Popular and the language rooms) is rendered above, so a
            // joined language room must not also appear here as one of "your" channels.
            val ownChannels = channels.filter { it.channelName !in com.kachat.app.models.FeaturedBroadcastChannels.INDEXED_NAMES }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item(key = "header:popular") {
                    // The retention note lives here since the in-room banner was removed to
                    // keep the chat itself clean (matches iOS).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Popular",
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.broadcast_popular_retention_note),
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                items(FeaturedBroadcastChannels.NAMES, key = { "popular:$it" }) { name ->
                    // Curated rooms are permanent (no Leave) with fixed 3-day retention (no
                    // gear) and indexer-backed history (no listen toggle) — the bell is the
                    // only control, same as iOS. They're auto-joined, so tapping always just
                    // opens the room; the join call below only covers the brief first-launch
                    // race before ensureFeaturedChannelsJoined has landed.
                    val channel = channels.firstOrNull { it.channelName == name }
                    Surface(
                        color = LocalAppColors.current.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (channel == null) broadcastViewModel.joinChannel(name)
                                navController.navigate("broadcast_channel/$name")
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("#$name", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                            }
                            if (channel != null) IconButton(onClick = {
                                val newValue = !channel.notifyEnabled
                                broadcastViewModel.setNotifyEnabled(channel.channelName, newValue)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (newValue) {
                                            "You'll get a notification for new messages in this broadcast as long as your app remains open"
                                        } else {
                                            "Notifications are off for this broadcast"
                                        }
                                    )
                                }
                            }) {
                                Icon(
                                    imageVector = if (channel.notifyEnabled) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                                    contentDescription = if (channel.notifyEnabled) "Turn off notifications" else "Turn on notifications",
                                    tint = if (channel.notifyEnabled) KaspaTeal else Color.Gray
                                )
                            }
                        }
                    }
                }
                // "Other Languages": a collapsed category inside Popular, so the section header's
                // 30-day retention note covers these rooms too, which it correctly does — they
                // are indexer-tracked exactly like the two above.
                item(key = "header:languages") {
                    Surface(
                        color = LocalAppColors.current.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { languagesExpanded = !languagesExpanded }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = null,
                                tint = KaspaTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Other Languages",
                                color = LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${FeaturedBroadcastChannels.LANGUAGE_NAMES.size}",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = LocalAppColors.current.textSecondary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(languagesRotation)
                            )
                        }
                    }
                }
                if (languagesExpanded) {
                    items(FeaturedBroadcastChannels.LANGUAGE_NAMES, key = { "language:$it" }) { name ->
                        val channel = channels.firstOrNull { it.channelName == name }
                        val notifyOn = channel?.notifyEnabled == true
                        Surface(
                            color = LocalAppColors.current.surface,
                            shape = RoundedCornerShape(16.dp),
                            // Deeper start padding than the cards above: these read as children
                            // of the "Other Languages" row they slid out from.
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .clickable {
                                    broadcastViewModel.ensureCuratedRoomJoined(name)
                                    navController.navigate("broadcast_channel/$name")
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        FeaturedBroadcastChannels.languageDisplayName(name) ?: "#$name",
                                        color = LocalAppColors.current.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "#$name",
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton(onClick = {
                                    val newValue = !notifyOn
                                    broadcastViewModel.setNotifyEnabledEnsuringJoined(name, newValue)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (newValue) {
                                                "You'll get a notification for new messages in this broadcast as long as your app remains open"
                                            } else {
                                                "Notifications are off for this broadcast"
                                            }
                                        )
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (notifyOn) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                                        contentDescription = if (notifyOn) "Turn off notifications" else "Turn on notifications",
                                        tint = if (notifyOn) KaspaTeal else Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "header:own") {
                    // iOS parity: the "+" sits on the same line as the section title and opens
                    // the exact same join/create dialog the toolbar button used to.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Your Channels",
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            channelInput = ""
                            broadcastViewModel.resetJoinChannelState()
                            showJoinDialog = true
                        }) {
                            Icon(Icons.Default.AddCircle, "Join Channel", tint = KaspaTeal)
                        }
                    }
                }
                if (ownChannels.isEmpty()) {
                    item(key = "own:empty") {
                        Text(
                            "No channels yet - tap + to join or create one.",
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    items(ownChannels, key = { it.channelName }) { channel ->
                        Surface(
                            color = LocalAppColors.current.surface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate("broadcast_channel/${channel.channelName}") }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("#${channel.channelName}", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = {
                                    val newValue = !channel.alwaysListen
                                    broadcastViewModel.setAlwaysListen(channel.channelName, newValue)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (newValue) {
                                                "You will now listen for new chats as long as your app remains open"
                                            } else {
                                                "You will no longer see messages in this broadcast unless you are in the broadcast at the same time chats come in"
                                            }
                                        )
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (channel.alwaysListen) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                        contentDescription = if (channel.alwaysListen) "Stop always listening" else "Always listen",
                                        tint = if (channel.alwaysListen) KaspaTeal else Color.Gray
                                    )
                                }
                                IconButton(onClick = {
                                    val newValue = !channel.notifyEnabled
                                    broadcastViewModel.setNotifyEnabled(channel.channelName, newValue)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (newValue) {
                                                "You'll get a notification for new messages in this broadcast as long as your app remains open"
                                            } else {
                                                "Notifications are off for this broadcast"
                                            }
                                        )
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (channel.notifyEnabled) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                                        contentDescription = if (channel.notifyEnabled) "Turn off notifications" else "Turn on notifications",
                                        tint = if (channel.notifyEnabled) KaspaTeal else Color.Gray
                                    )
                                }
                                IconButton(onClick = { retentionSettingsChannelName = channel.channelName }) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = stringResource(R.string.message_retention_settings),
                                        tint = LocalAppColors.current.textSecondary
                                    )
                                }
                                TextButton(onClick = { channelToLeave = channel.channelName }) {
                                    Text(stringResource(R.string.leave), color = LocalAppColors.current.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.join_or_create_a_channel), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.anyone_who_joins_the_same_channel),
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it },
                        placeholder = { Text(stringResource(R.string.channel_name), color = Color.DarkGray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (joinState.status == BroadcastViewModel.JoinChannelStatus.FAILED) {
                        Spacer(Modifier.height(8.dp))
                        Text(joinState.message ?: "Invalid channel name", color = Color(0xFFFF3B30), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                // Doesn't close the dialog itself — joinChannel() updates joinChannelState
                // asynchronously, so whether this succeeded isn't known yet at click time. The
                // LaunchedEffect above closes the dialog once SUCCESS actually arrives; on
                // FAILED it stays open showing the error text instead.
                TextButton(onClick = { broadcastViewModel.joinChannel(channelInput) }) {
                    Text(stringResource(R.string.join), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    channelToLeave?.let { channelName ->
        AlertDialog(
            onDismissRequest = { channelToLeave = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Leave #$channelName", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.leaving_this_broadcast_permanently_deletes_every),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    broadcastViewModel.leaveChannel(channelName)
                    channelToLeave = null
                }) {
                    Text(stringResource(R.string.leave_delete), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { channelToLeave = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    retentionSettingsChannelName?.let { channelName ->
        // Looked up live from `channels` (rather than captured at click time) so a stale snapshot
        // never overwrites a concurrent update; only used to seed the fields below, though, since
        // the fields themselves must survive unrelated recompositions (e.g. a new message arriving)
        // while the dialog is open without resetting whatever the user is mid-typing.
        val channel = channels.firstOrNull { it.channelName == channelName }
        if (channel != null) {
            val (initialAmount, initialUnit) = remember(channelName) { BroadcastRetention.toAmountAndUnit(channel.retentionMillis) }
            var amountText by remember(channelName) { mutableStateOf(initialAmount.toString()) }
            var selectedUnit by remember(channelName) { mutableStateOf(initialUnit) }
            var unitMenuExpanded by remember(channelName) { mutableStateOf(false) }

            val amount = amountText.toLongOrNull()
            val isValid = amount != null && amount in 1..selectedUnit.maxAmount

            AlertDialog(
                onDismissRequest = { retentionSettingsChannelName = null },
                containerColor = LocalAppColors.current.surface,
                title = { Text("Message Retention for #$channelName", color = LocalAppColors.current.textPrimary) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.how_long_messages_in_this_broadcast),
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { input -> amountText = input.filter { it.isDigit() }.take(9) },
                                singleLine = true,
                                isError = !isValid,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = LocalAppColors.current.textPrimary,
                                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                                    focusedBorderColor = KaspaTeal,
                                    unfocusedBorderColor = LocalAppColors.current.textSecondary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Box {
                                OutlinedButton(
                                    onClick = { unitMenuExpanded = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalAppColors.current.textPrimary)
                                ) {
                                    Text(selectedUnit.label)
                                }
                                DropdownMenu(
                                    expanded = unitMenuExpanded,
                                    onDismissRequest = { unitMenuExpanded = false },
                                    modifier = Modifier.background(LocalAppColors.current.surfaceVariant)
                                ) {
                                    BroadcastRetention.Unit.entries.forEach { unit ->
                                        DropdownMenuItem(
                                            text = { Text(unit.label, color = LocalAppColors.current.textPrimary) },
                                            onClick = {
                                                // Re-clamp the typed amount to the new unit's cap rather than clearing it,
                                                // so switching e.g. seconds -> hours after typing 200 lands on the 72-hour max.
                                                val current = amountText.toLongOrNull()
                                                if (current != null && current > unit.maxAmount) {
                                                    amountText = unit.maxAmount.toString()
                                                }
                                                selectedUnit = unit
                                                unitMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Max: ${selectedUnit.maxAmount} ${selectedUnit.label}",
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.longer_retention_means_more_messages_stay),
                            color = Color(0xFFF39C12),
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = isValid,
                        onClick = {
                            broadcastViewModel.setRetentionMillis(channelName, amount!! * selectedUnit.millisPerUnit)
                            retentionSettingsChannelName = null
                        }
                    ) {
                        Text(stringResource(R.string.save), color = if (isValid) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { retentionSettingsChannelName = null }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            )
        }
    }

    if (showBroadcastSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showBroadcastSettingsDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.broadcast_settings), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.kns_profile_pictures), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.shows_senders_kns_avatars_in_rooms),
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = showKnsAvatarsEnabled,
                            onCheckedChange = { broadcastViewModel.setShowKnsAvatarsEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = KaspaTeal, checkedTrackColor = KaspaTeal.copy(alpha = 0.5f))
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                    SettingsNavigationItem(
                        stringResource(R.string.hidden_broadcast_room_users),
                        Icons.Default.VisibilityOff,
                        hiddenSenders.size.toString(),
                        onClick = {
                            showBroadcastSettingsDialog = false
                            navController.navigate("hidden_broadcast_users")
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showBroadcastSettingsDialog = false }) {
                    Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BroadcastChannelScreen(
    channelName: String,
    onBack: () -> Unit,
    navController: NavController,
    broadcastViewModel: BroadcastViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
    settingsViewModel: com.kachat.app.viewmodels.SettingsViewModel = hiltViewModel()
) {
    val showFeeEstimate by settingsViewModel.showFeeEstimate.collectAsState()
    val messages by broadcastViewModel.getMessages(channelName).collectAsState(initial = emptyList())
    // Reactions aggregated per message txId — same shape as GroupChatScreen's groupReactionsByTxId.
    val channelReactions by broadcastViewModel.getReactions(channelName).collectAsState(initial = emptyList())
    val reactionsByTxId = remember(channelReactions) { channelReactions.groupBy { it.targetTxId } }
    val quickReactionEmojis by settingsViewModel.quickReactionEmojis.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    // Zero-balance funding gate — same behavior as the 1:1/group chat threads (confirmed 0 KAS
    // only, on-entry refresh + 10s re-poll while gated); see GiftClaimUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()
    val sendState by broadcastViewModel.sendBroadcastState.collectAsState()
    val voiceRecordingState by broadcastViewModel.voiceRecordingState.collectAsState()
    val messageText by broadcastViewModel.messageText.collectAsState()
    val estimatedFee by broadcastViewModel.estimatedFeeSompi.collectAsState()
    val senderProfiles by broadcastViewModel.senderProfiles.collectAsState()
    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()
    val showKnsAvatarsEnabled by broadcastViewModel.showKnsAvatarsEnabled.collectAsState()
    val replyingTo by broadcastViewModel.replyingTo.collectAsState()
    val kaspaExplorer by broadcastViewModel.kaspaExplorer.collectAsState()
    val networkFeeRate by broadcastViewModel.networkFeeRate.collectAsState()
    val feeRateOverride by broadcastViewModel.feeRateOverride.collectAsState()
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    // Same trick as 1:1 chat's fee pill — recover the mass implied by whatever's currently being
    // composed (text vs. voice) by dividing the live fee preview back out by the rate that
    // produced it, instead of duplicating estimatedFeeSompi's own calculation here.
    val effectiveRate = feeRateOverride?.toDouble() ?: networkFeeRate
    val openFeeEditor: (Long) -> Unit = { currentFeeSompi ->
        feeEditorInput = "%.8f".format(java.util.Locale.US, currentFeeSompi / 100_000_000.0)
        showFeeEditor = true
    }
    val uriHandler = LocalUriHandler.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    val jumpToReply: (String) -> Unit = { targetId ->
        val index = messages.indexOfFirst { it.id == targetId }
        if (index >= 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(index)
                highlightedMessageId = targetId
                delay(1200)
                if (highlightedMessageId == targetId) highlightedMessageId = null
            }
        }
    }

    // Swipe-left-to-reveal-timestamps (iMessage-style): dragging left across the whole message
    // list shifts every message row left by the same amount, uncovering a per-message time in the
    // strip of space that opens up on the right; releasing snaps everything back. revealOffsetPx
    // is negative-or-zero (never allowed to shift right past its resting position).
    val revealOffsetPx = remember { Animatable(0f) }
    val maxRevealOffsetPx = with(LocalDensity.current) { 64.dp.toPx() }
    // Guards every snapTo so a straggler delta dispatched after release can't cancel the settle
    // animation and leave the reveal stuck — see ChatThreadScreen's identical block.
    val isRevealDragging = remember { mutableStateOf(false) }
    // Release ALWAYS springs the rows back; a vertical scroll stealing the gesture forces it too.
    LaunchedEffect(isRevealDragging.value, listState.isScrollInProgress) {
        if (listState.isScrollInProgress) isRevealDragging.value = false
        if (!isRevealDragging.value) revealOffsetPx.animateTo(0f)
    }

    val micContext = LocalContext.current
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) broadcastViewModel.startVoiceRecording(channelName)
    }
    val startVoiceRecordingIfPermitted = {
        if (broadcastViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                broadcastViewModel.startVoiceRecording(channelName)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // First fill JUMPS to the newest message instantly - featured rooms hold the whole 30-day
    // window, and the old unconditional animateScrollToItem crawled the full history on every
    // open. After that, follow new arrivals only when the reader is already at the bottom, so
    // indexer backfill and live inserts never yank someone reading history (same policy as 1:1
    // chats).
    var hasPositionedAtLatest by remember(channelName) { mutableStateOf(false) }
    LaunchedEffect(channelName, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!hasPositionedAtLatest) {
            listState.scrollToItem(messages.size - 1)
            hasPositionedAtLatest = true
        } else {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= messages.size - 3 && !listState.isScrollInProgress) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Only show the "jump to latest" button once the last message isn't even partially
    // visible — a real, deliberate scroll away from the bottom to read history — not just a
    // transient viewport shrink. Tolerates a 1-item gap for the same reason as 1:1 chat's
    // equivalent check (see MessageBubble's screen in Screens.kt).
    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisibleIndex != null && lastVisibleIndex < messages.lastIndex - 1
        }
    }

    // Fee preview needs real UTXOs/fee-rate data, not just whatever was last fetched (which
    // could be empty/stale) — refresh once on entry, matching how 1:1 chats always have this
    // available rather than gating it behind a separate "payment mode" (broadcasts have none).
    LaunchedEffect(Unit) {
        broadcastViewModel.refreshUtxos()
    }

    // Live messages appear while this screen is open even if this channel isn't marked
    // always-listen — bounded to exactly as long as this composable is on screen. Featured
    // rooms additionally backfill from the broadcast indexer (once + every 8s) so history
    // sent while the app was closed shows up too.
    DisposableEffect(channelName) {
        broadcastViewModel.startLiveViewing(channelName)
        broadcastViewModel.startIndexerBackfill(channelName)
        onDispose {
            broadcastViewModel.stopLiveViewing()
            broadcastViewModel.stopIndexerBackfill()
        }
    }
    val roomDotColorHex by androidx.hilt.navigation.compose.hiltViewModel<com.kachat.app.viewmodels.ConnectionViewModel>().dotColorHex.collectAsState()
    var showRoomHiddenUsers by remember { mutableStateOf(false) }
    val roomHiddenSenders by broadcastViewModel.hiddenSenders.collectAsState()

    LaunchedEffect(myAddress) {
        myAddress?.let { broadcastViewModel.ensureSenderProfileFetched(it) }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("#$channelName", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    // Left-side clickable connection dot (matches iOS's navigationBarLeading
                    // ConnectionStatusIndicator - tapping it opens the connection status page),
                    // same back-arrow + dot Row as the 1:1 chat thread header.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(LocalAppColors.current.surface, CircleShape)
                                .clickable { navController.navigate("connection_status") },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(10.dp).background(Color(roomDotColorHex), CircleShape))
                        }
                    }
                },
                actions = {
                    // Per-room hidden users (4.0, matches iOS): manage who's hidden in THIS room.
                    IconButton(onClick = { showRoomHiddenUsers = true }) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = "Hidden users", tint = LocalAppColors.current.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            // Composer dims and goes inert while the zero-balance funding gate is up — see
            // Modifier.zeroBalanceComposerGate in GiftClaimUi.kt.
            Column(modifier = Modifier.background(LocalAppColors.current.background).navigationBarsPadding().imePadding().padding(8.dp).zeroBalanceComposerGate(fundingGate.active)) {
                if (sendState.status == BroadcastViewModel.SendBroadcastStatus.FAILED) {
                    Text(
                        sendState.message ?: "Failed to send",
                        color = Color(0xFFFF3B30),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                replyingTo?.let { reply ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(LocalAppColors.current.surface, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Replying to ${contactAliases[reply.senderAddress] ?: senderKnsNames[reply.senderAddress] ?: reply.senderAddress.takeLast(10)}",
                                color = KaspaTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                VoiceMessage.parseOrNull(reply.content)?.let { "🎤 Audio message" }
                                    ?: MessageReply.parseOrNull(reply.content)?.text
                                    ?: reply.content,
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { broadcastViewModel.cancelReply() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_reply), tint = LocalAppColors.current.textSecondary)
                        }
                    }
                }
                if (voiceRecordingState.status == BroadcastViewModel.VoiceRecordingStatus.RECORDING) {
                    if (showFeeEstimate && estimatedFee != null) {
                        Surface(
                            color = LocalAppColors.current.surface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 8.dp)
                                .clickable { openFeeEditor(estimatedFee ?: 0L) }
                        ) {
                            Text(
                                text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(LocalAppColors.current.surface)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = { broadcastViewModel.cancelVoiceRecording() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_recording), tint = Color(0xFFFF3B30))
                        }
                        Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
                        Text(
                            text = "Recording... ${formatRecordingElapsed(voiceRecordingState.elapsedMs)}",
                            color = LocalAppColors.current.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { broadcastViewModel.stopAndSendVoiceRecording(channelName) },
                            modifier = Modifier.size(40.dp).background(KaspaTeal, CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send), tint = Color.Black, modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    if (showFeeEstimate && estimatedFee != null && messageText.isNotEmpty()) {
                        Surface(
                            color = LocalAppColors.current.surface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 8.dp)
                                .clickable { openFeeEditor(estimatedFee ?: 0L) }
                        ) {
                            Text(
                                text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { broadcastViewModel.setMessageText(it) },
                            placeholder = { Text("Message #$channelName", color = Color.DarkGray) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = LocalAppColors.current.textPrimary,
                                unfocusedTextColor = LocalAppColors.current.textPrimary,
                                focusedBorderColor = KaspaTeal,
                                unfocusedBorderColor = LocalAppColors.current.textSecondary
                            )
                        )
                        val sending = sendState.status == BroadcastViewModel.SendBroadcastStatus.SENDING
                        if (messageText.isEmpty()) {
                            IconButton(onClick = { startVoiceRecordingIfPermitted() }) {
                                Icon(Icons.Default.Mic, "Record voice message", tint = KaspaTeal)
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (!sending && messageText.isNotBlank()) {
                                        broadcastViewModel.sendBroadcast(channelName, messageText)
                                        broadcastViewModel.setMessageText("")
                                    }
                                },
                                enabled = !sending
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (sending) Color.Gray else KaspaTeal)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        // The in-room retention banner was removed to keep the chat clean; the retention note
        // now lives next to the "Popular" header on the broadcast list (matches iOS).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        coroutineScope.launch {
                            if (isRevealDragging.value) {
                                revealOffsetPx.snapTo((revealOffsetPx.value + delta).coerceIn(-maxRevealOffsetPx, 0f))
                            }
                        }
                    },
                    onDragStarted = { isRevealDragging.value = true },
                    // The settle LaunchedEffect above owns the spring-back — flipping the flag
                    // both triggers it and disarms any still-queued snapTo deltas.
                    onDragStopped = { isRevealDragging.value = false }
                )
        ) {
        if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No messages yet. Be the first to post in #$channelName",
                    color = LocalAppColors.current.textSecondary,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val showDateDivider = index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, message.blockTimestamp)
                    if (showDateDivider) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Surface(color = LocalAppColors.current.surface, shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    ChatTimeFormat.formatDateDivider(message.blockTimestamp),
                                    color = LocalAppColors.current.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    val isMine = message.senderAddress == myAddress
                    val replyContent = remember(message.content) { MessageReply.parseOrNull(message.content) }
                    val displayContent = replyContent?.text ?: message.content
                    val voiceContent = remember(displayContent) { VoiceMessage.parseOrNull(displayContent) }
                    // Same lone-URL detection 1:1's MessageBubble uses: nothing but a link means
                    // the preview card replaces the plain-text bubble entirely; a link mixed with
                    // other text keeps the bubble and stacks the card below it (like groups).
                    // Both checked only for content short enough to lay out inline — scanning a
                    // huge wall of text for links is wasted work (see MessageBubble's same guard).
                    val isEntirelyLinkMessage = remember(displayContent, voiceContent) {
                        voiceContent == null && displayContent.length <= MESSAGE_TEXT_TRUNCATION_THRESHOLD && TextLinkify.isEntirelyLink(displayContent)
                    }
                    val separateLinkPreviewUrl = remember(displayContent, voiceContent, isEntirelyLinkMessage) {
                        if (voiceContent == null && !isEntirelyLinkMessage && displayContent.length <= MESSAGE_TEXT_TRUNCATION_THRESHOLD) {
                            TextLinkify.findUrls(displayContent).firstOrNull()?.uri
                        } else null
                    }
                    val messageReactions = reactionsByTxId[message.id] ?: emptyList()
                    var showMenu by remember { mutableStateOf(false) }
                    var showQuickReactionBar by remember { mutableStateOf(false) }
                    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
                    val clipboardManager = LocalClipboardManager.current
                    val menuContext = LocalContext.current

                    LaunchedEffect(message.senderAddress) {
                        broadcastViewModel.ensureSenderProfileFetched(message.senderAddress)
                    }

                    var showAvatarMenu by remember { mutableStateOf(false) }
                    var avatarMenuAnchor by remember { mutableStateOf(Offset.Zero) }

                    val avatar: @Composable () -> Unit = {
                        Box(
                            modifier = Modifier.onGloballyPositioned { coords ->
                                avatarMenuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
                            }
                        ) {
                            ContactAvatar(
                                imageUrl = if (showKnsAvatarsEnabled) senderProfiles[message.senderAddress] else null,
                                fallbackText = message.senderAddress.takeLast(8),
                                size = 32.dp,
                                modifier = Modifier.clickable { showAvatarMenu = true }
                            )
                            if (showAvatarMenu) {
                                CenteredOptionsMenu(onDismissRequest = { showAvatarMenu = false }, anchor = avatarMenuAnchor) {
                                    PopupMenuRow(Icons.Default.Person, stringResource(R.string.view_profile)) {
                                        broadcastViewModel.openSenderProfile(message.senderAddress) { address ->
                                            navController.navigate("chat_info/$address?fromBroadcast=true")
                                        }
                                        showAvatarMenu = false
                                    }
                                    if (!isMine) {
                                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        PopupMenuRow(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.open_chat)) {
                                            broadcastViewModel.openSenderProfile(message.senderAddress) { address ->
                                                navController.navigate("chat/$address")
                                            }
                                            showAvatarMenu = false
                                        }
                                    }
                                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                    PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_address)) {
                                        clipboardManager.setText(AnnotatedString(message.senderAddress))
                                        com.kachat.app.util.showAddressCopiedToast(menuContext, message.senderAddress)
                                        showAvatarMenu = false
                                    }
                                    if (!isMine) {
                                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        PopupMenuRow(painterResource(R.drawable.ic_kaspa_logo), stringResource(R.string.pay_in_kaspa), iconTint = Color.Unspecified) {
                                            broadcastViewModel.openSenderProfile(message.senderAddress) { address ->
                                                navController.navigate("chat/$address?paymentMode=true")
                                            }
                                            showAvatarMenu = false
                                        }
                                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        PopupMenuRow(Icons.Default.VisibilityOff, stringResource(R.string.hide_user), labelColor = Color(0xFFFF3B30), iconTint = Color(0xFFFF3B30)) {
                                            // Per-room since 4.0: hides this sender in THIS room only.
                                            broadcastViewModel.hideSender(message.senderAddress, channelName)
                                            showAvatarMenu = false
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val highlightColor by animateColorAsState(
                        if (message.id == highlightedMessageId) KaspaTeal.copy(alpha = 0.18f) else Color.Transparent,
                        label = "messageHighlight"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(highlightColor, RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = remember(message.blockTimestamp) { ChatTimeFormat.formatMessageTime(message.blockTimestamp) },
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                                .alpha((-revealOffsetPx.value / maxRevealOffsetPx).coerceIn(0f, 1f))
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(revealOffsetPx.value.toInt(), 0) },
                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Bottom
                        ) {
                        if (!isMine) {
                            avatar()
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(
                            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                            modifier = Modifier.onGloballyPositioned { coords ->
                                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
                            }
                        ) {
                            Text(
                                contactAliases[message.senderAddress] ?: senderKnsNames[message.senderAddress] ?: message.senderAddress.takeLast(10),
                                color = KaspaTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    start = if (isMine) 0.dp else 14.dp,
                                    end = if (isMine) 14.dp else 0.dp,
                                    bottom = 2.dp
                                )
                            )
                            if (replyContent != null) {
                                Surface(
                                    color = LocalAppColors.current.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .padding(bottom = 4.dp)
                                        .widthIn(max = 240.dp)
                                        .clickable { jumpToReply(replyContent.replyToId) }
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            contactAliases[replyContent.replyToSender] ?: senderKnsNames[replyContent.replyToSender] ?: replyContent.replyToSender.takeLast(10),
                                            color = KaspaTeal,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            replyContent.replyToPreview,
                                            color = LocalAppColors.current.textSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Box {
                                if (voiceContent != null) {
                                    AudioBubble(
                                        voiceContent,
                                        isSent = isMine,
                                        onLongPress = { showMenu = true },
                                        onDoubleClick = { showQuickReactionBar = true }
                                    )
                                } else if (displayContent.length > MESSAGE_TEXT_TRUNCATION_THRESHOLD) {
                                    // See MESSAGE_TEXT_TRUNCATION_THRESHOLD's doc comment in Screens.kt -
                                    // broadcast rooms are public/unencrypted, so a huge wall of text (e.g.
                                    // stray base64) landing here is if anything more likely than in a
                                    // private chat.
                                    var showFullText by remember { mutableStateOf(false) }
                                    Column(
                                        modifier = Modifier
                                            .background(
                                                if (isMine) KaspaTeal else LocalAppColors.current.surface,
                                                RoundedCornerShape(20.dp)
                                            )
                                            .combinedClickable(
                                                onClick = { showFullText = true },
                                                onLongClick = { showMenu = true },
                                                onDoubleClick = { showQuickReactionBar = true }
                                            )
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                            .widthIn(max = 280.dp)
                                    ) {
                                        Text(
                                            displayContent.take(MESSAGE_TEXT_PREVIEW_LENGTH) + "…",
                                            color = if (isMine) Color.Black else LocalAppColors.current.textPrimary
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.show_more),
                                            color = if (isMine) LocalAppColors.current.divider else KaspaTeal,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    if (showFullText) {
                                        FullMessageTextDialog(
                                            text = displayContent,
                                            onDismiss = { showFullText = false },
                                            onCopy = { clipboardManager.setText(AnnotatedString(displayContent)) }
                                        )
                                    }
                                } else if (isEntirelyLinkMessage) {
                                    // Message is nothing but a link — the shared preview card
                                    // (bare media for image/video, attachment card for
                                    // audio/files) replaces the plain-text bubble entirely,
                                    // exactly like 1:1/group bubbles. `fallbackText` keeps the
                                    // raw link visible/tappable if no preview data is found.
                                    // autoFetch = false for ALL broadcast messages (even own):
                                    // channel posters are strangers by definition, and fetching
                                    // their URL on render would leak every reader's IP to it.
                                    // Tap-to-load instead (2026-08 audit, decision 5A).
                                    LinkPreviewCard(
                                        url = TextLinkify.findUrls(displayContent).first().uri,
                                        txId = message.id,
                                        kaspaExplorer = kaspaExplorer,
                                        fallbackText = displayContent,
                                        onDoubleTap = { showQuickReactionBar = true },
                                        autoFetch = false
                                    )
                                } else {
                                    var textLayoutResult by remember(displayContent) { mutableStateOf<TextLayoutResult?>(null) }
                                    // Sent bubbles are teal with black text/links for contrast —
                                    // matches 1:1/group chats' treatment of the same case.
                                    val linkColor = if (isMine) Color.Black else KaspaTeal
                                    val annotatedBody = remember(displayContent, isMine) {
                                        buildAnnotatedString {
                                            append(displayContent)
                                            for (match in TextLinkify.findUrls(displayContent)) {
                                                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                                                addStringAnnotation("URL", match.uri, match.range.first, match.range.last + 1)
                                            }
                                        }
                                    }
                                    Column(
                                        modifier = Modifier
                                            .background(
                                                if (isMine) KaspaTeal else LocalAppColors.current.surface,
                                                RoundedCornerShape(20.dp)
                                            )
                                            .widthIn(max = 280.dp)
                                    ) {
                                        Text(
                                            annotatedBody,
                                            color = if (isMine) Color.Black else LocalAppColors.current.textPrimary,
                                            modifier = Modifier
                                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                                .pointerInput(annotatedBody) {
                                                    detectTapGestures(
                                                        onLongPress = { showMenu = true },
                                                        onDoubleTap = { showQuickReactionBar = true },
                                                        onTap = { offset ->
                                                            val layout = textLayoutResult ?: return@detectTapGestures
                                                            val charOffset = layout.getOffsetForPosition(offset)
                                                            annotatedBody.getStringAnnotations("URL", charOffset, charOffset)
                                                                .firstOrNull()?.let { uriHandler.openUri(it.item) }
                                                        }
                                                    )
                                                },
                                            onTextLayout = { textLayoutResult = it }
                                        )
                                    }
                                }

                                if (showMenu) {
                                    CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
                                        PopupMenuRow(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.reply)) {
                                            broadcastViewModel.startReplyTo(message)
                                            showMenu = false
                                        }
                                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_message)) {
                                            clipboardManager.setText(AnnotatedString(displayContent))
                                            showMenu = false
                                        }
                                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        PopupMenuRow(Icons.Default.Public, stringResource(R.string.view_in_explorer)) {
                                            uriHandler.openUri(kaspaExplorer.txUrl(message.id))
                                            showMenu = false
                                        }
                                        if (isMine && message.deliveryStatus == "failed") {
                                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                            PopupMenuRow(Icons.Default.Refresh, stringResource(R.string.retry_send)) {
                                                broadcastViewModel.retryBroadcast(message)
                                                showMenu = false
                                            }
                                        }
                                    }
                                }

                                // A small corner badge rather than a row below the bubble —
                                // stacking it as a separate row would grow the Column past the
                                // bubble's own height, throwing off the avatar's bottom-alignment
                                // in the outer Row (the exact bug the old always-visible timestamp
                                // row caused, see git history).
                                if (isMine) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 4.dp, y = 4.dp)
                                            .size(14.dp)
                                            .background(Color.Black, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when (message.deliveryStatus) {
                                            "failed" -> Icon(
                                                imageVector = Icons.Default.Error,
                                                contentDescription = stringResource(R.string.failed_to_send),
                                                tint = Color(0xFFFF3B30),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            "pending" -> Icon(
                                                imageVector = Icons.Default.Schedule,
                                                contentDescription = stringResource(R.string.sending),
                                                tint = LocalAppColors.current.textSecondary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            else -> Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF4CD964),
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }

                                // Anchored INSIDE the bubble's own wrap-content Box, exactly like
                                // 1:1's MessageBubble - so BottomStart/BottomEnd resolve against
                                // the bubble itself. It used to live below this Box inside a
                                // `Box(Modifier.fillMaxWidth())`, which resolved the alignment
                                // against the full row width instead: the pill flew to the screen
                                // edge (up to ~280dp from a short bubble), and stretching the
                                // Column to full width also moved the `menuAnchor` captured on it,
                                // so the long-press menu and the double-tap QuickReactionBar jumped
                                // to the left edge on any message that already had a reaction.
                                if (messageReactions.isNotEmpty()) {
                                    ReactionPill(
                                        reactions = messageReactions,
                                        myAddress = myAddress,
                                        modifier = Modifier
                                            .align(if (isMine) Alignment.BottomStart else Alignment.BottomEnd)
                                            .offset(y = 10.dp)
                                    )
                                }
                            }

                            // The pill is offset ~10dp below the bubble Box and offset reserves no
                            // layout space, so reserve it here - otherwise the pill overlaps the
                            // link preview card / next message below it.
                            if (messageReactions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            // A link mixed with other text keeps the bubble above and stacks the
                            // shared preview card below it, as its own sibling — same placement
                            // (and same reasoning) as 1:1's MessageBubble/group's GroupMessageBubble.
                            separateLinkPreviewUrl?.let { url ->
                                // Tap-to-load for all broadcast previews - see the entire-link
                                // branch above for why.
                                LinkPreviewCard(url = url, txId = message.id, kaspaExplorer = kaspaExplorer, onDoubleTap = { showQuickReactionBar = true }, autoFetch = false)
                            }

                            if (showQuickReactionBar) {
                                QuickReactionBar(
                                    onDismissRequest = { showQuickReactionBar = false },
                                    anchor = menuAnchor,
                                    onReact = { emoji ->
                                        // Tapping your active emoji removes it; any other emoji
                                        // adds/replaces — same toggle rule as 1:1/group chats.
                                        val existing = messageReactions.firstOrNull { it.reactorAddress == myAddress }
                                        val action = if (existing?.emoji == emoji) "remove" else "add"
                                        broadcastViewModel.sendReaction(channelName, message.id, emoji, action)
                                    },
                                    onReply = { broadcastViewModel.startReplyTo(message) },
                                    emojis = quickReactionEmojis
                                )
                            }

                            // A reaction (not the message) that failed to send: red "Retry" under the
                            // message, paired with the error icon on the reaction pill. Aligned with
                            // ColumnScope.align (NOT a fillMaxWidth Box) so it sits under the pill's
                            // side of the bubble without stretching this Column to the full row width.
                            messageReactions.firstOrNull { it.deliveryStatus == "failed" }?.let { failedReaction ->
                                Text(
                                    text = stringResource(R.string.retry),
                                    color = Color(0xFFFF3B30),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(if (isMine) Alignment.Start else Alignment.End)
                                        .padding(top = 2.dp)
                                        .clickable { broadcastViewModel.retryReaction(failedReaction) }
                                )
                            }
                        }
                        if (isMine) {
                            Spacer(Modifier.width(8.dp))
                            avatar()
                        }
                        }
                    }
                }
            }
        }

        if (showScrollToBottom && messages.isNotEmpty()) {
            IconButton(
                onClick = {
                    coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(44.dp)
                    .background(LocalAppColors.current.surface, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.scroll_to_latest),
                    tint = LocalAppColors.current.textPrimary
                )
            }
        }

        // Zero-balance funding gate card — same as the 1:1/group threads: composer dimmed
        // below, the room stays readable and scrollable, gone the moment the chatting balance
        // confirms as > 0.
        if (fundingGate.active) {
            ZeroBalanceFundingCard(
                walletAddress = fundingGate.chattingAddress,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }
        }
    }

    if (showRoomHiddenUsers) {
        AlertDialog(
            onDismissRequest = { showRoomHiddenUsers = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Hidden in #$channelName", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                val rows = roomHiddenSenders.filter { it.channelName.isEmpty() || it.channelName == channelName }
                if (rows.isEmpty()) {
                    Text(
                        "No hidden users in this room. Hide someone from their avatar menu.",
                        color = LocalAppColors.current.textSecondary
                    )
                } else {
                    Column {
                        rows.forEach { row ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            ) {
                                Text(
                                    contactAliases[row.senderAddress] ?: senderKnsNames[row.senderAddress] ?: row.senderAddress.takeLast(10),
                                    color = LocalAppColors.current.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { broadcastViewModel.unhideSender(row.senderAddress, channelName) }) {
                                    Text(stringResource(R.string.unhide), color = KaspaTeal, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRoomHiddenUsers = false }) {
                    Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showFeeEditor) {
        AlertDialog(
            onDismissRequest = { showFeeEditor = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.adjust_network_fee), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.if_the_network_is_busy_a),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = feeEditorInput,
                        onValueChange = { feeEditorInput = it },
                        label = { Text(stringResource(R.string.fee_kas)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kas = feeEditorInput.toDoubleOrNull()
                    val currentFeeSompi = estimatedFee ?: 0L
                    if (kas != null && kas > 0 && currentFeeSompi > 0 && effectiveRate > 0) {
                        val impliedMass = currentFeeSompi / effectiveRate
                        val desiredFeeSompi = Math.round(kas * 100_000_000.0)
                        broadcastViewModel.setFeeRateOverride(kotlin.math.ceil(desiredFeeSompi / impliedMass).toLong())
                    } else {
                        broadcastViewModel.setFeeRateOverride(null)
                    }
                    showFeeEditor = false
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { broadcastViewModel.setFeeRateOverride(null); showFeeEditor = false }) {
                        Text(stringResource(R.string.use_default), color = LocalAppColors.current.textSecondary)
                    }
                    TextButton(onClick = { showFeeEditor = false }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }
    }
}

/** Manages senders hidden from every broadcast room (set via "Hide User" on an avatar) — reachable from the main Settings tab, underneath Archived Chats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenBroadcastUsersScreen(
    onBack: () -> Unit,
    broadcastViewModel: BroadcastViewModel = hiltViewModel()
) {
    val hiddenSenders by broadcastViewModel.hiddenSenders.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()
    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()

    // Same alias -> KNS name -> short address fallback used inside a broadcast room — a hidden
    // user's name here should read the same as it would if they weren't hidden. KNS names aren't
    // fetched anywhere else for these addresses (no message list is rendering them once hidden),
    // so this screen has to kick that lookup off itself.
    LaunchedEffect(hiddenSenders) {
        hiddenSenders.forEach { broadcastViewModel.ensureSenderProfileFetched(it.senderAddress) }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.hidden_broadcast_room_users), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        if (hiddenSenders.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.no_hidden_users),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.users_you_hide_from_a_broadcast),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(hiddenSenders, key = { "${it.senderAddress}|${it.channelName}" }) { row ->
                    val address = row.senderAddress
                    Surface(
                        color = LocalAppColors.current.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    contactAliases[address] ?: senderKnsNames[address] ?: address.takeLast(10),
                                    color = LocalAppColors.current.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                // Per-room since 4.0 - "" rows are legacy every-room hides.
                                Text(
                                    if (row.channelName.isEmpty()) stringResource(R.string.hidden_in_all_rooms)
                                    else "#${row.channelName}",
                                    color = LocalAppColors.current.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(onClick = { broadcastViewModel.unhideSender(address, row.channelName) }) {
                                Text(stringResource(R.string.unhide), color = KaspaTeal, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
