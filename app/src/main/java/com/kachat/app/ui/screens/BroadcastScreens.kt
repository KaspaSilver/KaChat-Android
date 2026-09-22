package com.kachat.app.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Drafts
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Broadcast room share links (`kachat://broadcast/<channel>`,
 * `https://kachat.app/broadcast/<channel>`) land here — from an inbound system intent
 * (MainActivity) or from a tap on an in-app invite card ([openKaChatLink]). MainShell watches
 * [pendingChannel] and navigates; the room screen itself consumes [consumeJoinRequest] so a room
 * the user isn't in yet is created/joined before it opens, landing in their own channel list.
 *
 * The Child Mode check lives with the navigation in MainShell, exactly like the KaPosts link and
 * broadcast-notification paths, so a link can never route into a hidden feature.
 */
object BroadcastDeepLink {
    private val _pendingChannel = MutableStateFlow<String?>(null)
    val pendingChannel: StateFlow<String?> = _pendingChannel.asStateFlow()

    /** Channels a link asked for that aren't curated — joined on open so they stick around. */
    private val joinRequests = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Gates an untrusted channel name through [KaChatLink.sanitizeChannelName] (which is
     * [MessageProtocol.normalizeChannelName] + [MessageProtocol.isValidChannelName] plus the
     * route/rendering safety rules). Returns false — and does nothing at all — for a malformed or
     * hostile name, rather than joining it.
     */
    fun request(rawName: String): Boolean {
        val name = KaChatLink.sanitizeChannelName(rawName) ?: return false
        // Curated rooms are permanent fixtures that always exist, so they just open.
        if (name !in FeaturedBroadcastChannels.INDEXED_NAMES) joinRequests.add(name)
        _pendingChannel.value = name
        return true
    }

    fun consumePending() {
        _pendingChannel.value = null
    }

    fun consumeJoinRequest(channelName: String): Boolean = joinRequests.remove(channelName)

    fun clearJoinRequests() {
        joinRequests.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BroadcastListScreen(
    navController: NavController,
    onBack: () -> Unit,
    /** True when this is the Chats screen's Public Chats tab rather than a destination of its
     *  own: the Chats header is already up, and back belongs to the Chats screen. */
    embeddedInChats: Boolean = false,
    broadcastViewModel: BroadcastViewModel = hiltViewModel()
) {
    // The back arrow is gone from the header (iOS has none), so system back carries what it
    // did: from the Kaspa Hub this returns to the grid rather than leaving the Hub entirely.
    if (!embeddedInChats) BackHandler(onBack = onBack)

    val channels by broadcastViewModel.joinedChannels.collectAsState()
    val joinState by broadcastViewModel.joinChannelState.collectAsState()
    var showJoinDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
    var channelToLeave by remember { mutableStateOf<String?>(null) }
    /** The room whose long-press sheet is up. */
    var roomActionTarget by remember { mutableStateOf<String?>(null) }
    val roomClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
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
        // The same floating button the Chats and Group Chats pages carry, here for joining or
        // creating a room (iOS 6639a0b). Drawn by this page because it owns the join dialog.
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    channelInput = ""
                    broadcastViewModel.resetJoinChannelState()
                    showJoinDialog = true
                },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(Icons.Default.AddComment, contentDescription = "Join or create a public room", modifier = Modifier.size(28.dp))
            }
        },
        topBar = {
            // Embedded, the Chats header and its tab row are already above this.
            if (!embeddedInChats) {
                MainPageHeader(
                    title = stringResource(R.string.broadcasts),
                    // No back arrow, matching iOS: Public Chats is a browsing destination you
                    // arrive at, not something you were pushed into. System back still runs
                    // onBack (see the BackHandler above), which is how the Hub gets back to
                    // its grid.
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Rooms laid out like the Chats and Group Chats lists (iOS 83286b3): the two curated
            // rooms pinned on top, every other joined room below by latest activity, then the
            // default language rooms not opened yet - each its own row, no dropdown; any default
            // room can be switched off in Public Chats settings. Keys carry a
            // ':' prefix, which a channel name can never contain, so nothing can collide.
            val summaries by broadcastViewModel.roomSummaries.collectAsState()
            val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()
            val contactAliases by broadcastViewModel.contactAliases.collectAsState()
            val myAddressForRows = remember { runCatching { broadcastViewModel.myAddress() }.getOrNull() }
            val featured = com.kachat.app.models.FeaturedBroadcastChannels.NAMES
            // Default rooms switched off in Public Chats settings stay out of the list entirely.
            val hiddenCurated by broadcastViewModel.hiddenCuratedRooms.collectAsState()
            fun lastActivity(channel: com.kachat.app.models.BroadcastChannelEntity): Long =
                summaries[channel.channelName]?.lastMessage?.blockTimestamp ?: channel.joinedAt
            val shownChannels = channels.filter { it.channelName !in hiddenCurated }
            val listed = featured.mapNotNull { name -> shownChannels.firstOrNull { it.channelName == name } } +
                shownChannels.filter { it.channelName !in featured }.sortedByDescending(::lastActivity)
            val joinedNames = channels.map { it.channelName }.toSet()
            val unjoinedLanguages = com.kachat.app.models.FeaturedBroadcastChannels.LANGUAGE_NAMES
                .filter { it !in joinedNames && it !in hiddenCurated }
            fun senderName(address: String): String = when {
                address == myAddressForRows -> "You"
                !contactAliases[address].isNullOrBlank() -> contactAliases[address]!!
                !senderKnsNames[address].isNullOrBlank() -> senderKnsNames[address]!!
                else -> address.takeLast(8)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Room at the bottom so the last row never sits under the floating button.
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            ) {
                items(listed, key = { "room:${it.channelName}" }) { channel ->
                    val summary = summaries[channel.channelName]
                    val last = summary?.lastMessage
                    LaunchedEffect(last?.senderAddress) {
                        last?.senderAddress?.let { broadcastViewModel.ensureSenderProfileFetched(it) }
                    }
                    PublicChatRow(
                        channelName = channel.channelName,
                        notifyOff = !channel.notifyEnabled,
                        preview = last?.let { "${senderName(it.senderAddress)}: ${publicRoomPreview(it.content)}" },
                        emptyText = "No messages yet",
                        timeText = last?.let { publicRoomTime(it.blockTimestamp) },
                        unread = summary?.unreadCount ?: 0,
                        onClick = { navController.navigate("broadcast_channel/${channel.channelName}") },
                        onLongClick = { roomActionTarget = channel.channelName },
                    )
                }

                items(unjoinedLanguages, key = { "language:$it" }) { name ->
                    PublicChatRow(
                        channelName = name,
                        notifyOff = false,
                        preview = null,
                        emptyText = com.kachat.app.models.FeaturedBroadcastChannels.languageDisplayName(name)
                            ?.let { "$it · tap to open" } ?: "Tap to open",
                        timeText = null,
                        unread = 0,
                        onClick = {
                            broadcastViewModel.ensureCuratedRoomJoined(name)
                            navController.navigate("broadcast_channel/$name")
                        },
                        onLongClick = null,
                    )
                }

                item(key = "footer:note") {
                    Text(
                        "Public rooms are open to everyone. #kaspa, #kachat-bugs and the language rooms keep 30 days of history.",
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    // The long-press sheet, the same shape as a group's: read state, notifications, the room
    // link, and - for rooms you added yourself - listening, retention and delete. The curated
    // rooms are permanent, so they offer no delete.
    roomActionTarget?.let { name ->
        val channel = channels.firstOrNull { it.channelName == name }
        val isCurated = name in com.kachat.app.models.FeaturedBroadcastChannels.INDEXED_NAMES
        val notifyOn = channel?.notifyEnabled == true
        val summaries by broadcastViewModel.roomSummaries.collectAsState()
        val hasUnread = (summaries[name]?.unreadCount ?: 0) > 0
        fun say(text: String) { coroutineScope.launch { snackbarHostState.showSnackbar(text) } }
        ActionSheetContainer(title = "#$name", subtitle = null, onDismiss = { roomActionTarget = null }) {
            if (hasUnread) {
                ActionSheetRow(icon = Icons.Default.Drafts, title = "Mark as Read", subtitle = "Clears the unread badge on this room.") {
                    roomActionTarget = null
                    broadcastViewModel.markRoomRead(name)
                }
            } else {
                ActionSheetRow(icon = Icons.Default.MarkEmailUnread, title = "Mark as Unread", subtitle = "Puts the unread badge back so you come across it again.") {
                    roomActionTarget = null
                    broadcastViewModel.markRoomUnread(name)
                }
            }
            if (channel != null) {
                ActionSheetRow(
                    icon = if (notifyOn) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                    title = if (notifyOn) "Turn Off Notifications" else "Turn On Notifications",
                    subtitle = when {
                        notifyOn -> "No notification for new messages in this room."
                        isCurated -> "Notifies you of new messages, even when the app is closed."
                        else -> "Notifies you of new messages while the app is open."
                    },
                ) {
                    roomActionTarget = null
                    broadcastViewModel.setNotifyEnabled(name, !notifyOn)
                    say(if (notifyOn) "Notifications are off for this room" else "Notifications are on for this room")
                }
            }
            ActionSheetRow(icon = Icons.Default.Link, title = "Copy Room Link", subtitle = "A kachat.app link that opens this room.") {
                roomActionTarget = null
                roomClipboard.setText(androidx.compose.ui.text.AnnotatedString(KaChatLink.broadcastWebUrl(name)))
                say("Room link copied")
            }
            // No listening or retention rows: the bell is the one control. A room with
            // notifications on is listened to while the app is open, which is what lets it notify
            // and count unread (iOS ac34790).
            if (channel != null && !isCurated) {
                ActionSheetRow(
                    icon = Icons.Default.Delete,
                    title = "Delete",
                    subtitle = "Removes this room and its messages from this device.",
                    tint = Color(0xFFFF3B30),
                ) {
                    roomActionTarget = null
                    channelToLeave = name
                }
            }
        }
    }

    if (showJoinDialog) {
        // Joining and creating are the same action - there is no ownership protocol, so a name
        // nobody has used becomes a room the moment you post in it. Worth a sentence, which an
        // AlertDialog's cramped text slot never gave it room for.
        ActionSheetContainer(
            title = stringResource(R.string.join_or_create_a_channel),
            subtitle = stringResource(R.string.anyone_who_joins_the_same_channel),
            onDismiss = { showJoinDialog = false },
        ) {
            OutlinedTextField(
                value = channelInput,
                onValueChange = { channelInput = it },
                placeholder = { Text(stringResource(R.string.channel_name), color = Color.DarkGray) },
                prefix = { Text("#", color = LocalAppColors.current.textSecondary) },
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
                Text(
                    joinState.message ?: "Invalid channel name",
                    color = Color(0xFFFF3B30),
                    fontSize = 12.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showJoinDialog = false },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textPrimary)
                }
                // Does NOT close the sheet: joinChannel() updates joinChannelState
                // asynchronously, so whether it worked is not known at click time. The
                // LaunchedEffect above closes it on SUCCESS; on FAILED it stays, showing why.
                Button(
                    onClick = { broadcastViewModel.joinChannel(channelInput) },
                    enabled = channelInput.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                ) {
                    Text(stringResource(R.string.join), fontWeight = FontWeight.Bold)
                }
            }
        }
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

}

/**
 * Public Chats settings, behind the gear on the Public Chats tab: every default room with a
 * switch. Off takes the room out of the list and silences it for good; on brings it back.
 * Mirrors iOS's PublicChatsSettingsView.
 */
@Composable
fun PublicChatsSettingsSheet(
    onDismiss: () -> Unit,
    broadcastViewModel: BroadcastViewModel = hiltViewModel(),
) {
    val hidden by broadcastViewModel.hiddenCuratedRooms.collectAsState()
    val colors = LocalAppColors.current
    ActionSheetContainer(title = "Public Chats", subtitle = null, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            @Composable
            fun roomSwitch(name: String) {
                val language = com.kachat.app.models.FeaturedBroadcastChannels.languageDisplayName(name)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("#$name", color = colors.textPrimary, fontSize = 16.sp)
                        if (language != null) Text(language, color = colors.textSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = name !in hidden,
                        onCheckedChange = { shown -> broadcastViewModel.setCuratedRoomShown(name, shown) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = KaspaTeal),
                    )
                }
            }
            Text("Popular", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            com.kachat.app.models.FeaturedBroadcastChannels.NAMES.forEach { roomSwitch(it) }
            Text(
                "A room that is switched off no longer appears in Public Chats and never sends a notification. Switch it back on at any time.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text("Other Languages", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 2.dp))
            com.kachat.app.models.FeaturedBroadcastChannels.LANGUAGE_NAMES.forEach { roomSwitch(it) }
        }
    }
}

/**
 * One room, laid out like a chat in the Chats list: a # avatar, the room name, the newest message
 * with who sent it and when, a bell-off mark, and the unread badge. Mirrors iOS's PublicChatRow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PublicChatRow(
    channelName: String,
    notifyOff: Boolean,
    preview: String?,
    emptyText: String,
    timeText: String?,
    unread: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(50.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("#", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#$channelName",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (notifyOff) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.NotificationsOff, contentDescription = "Notifications off", tint = colors.textSecondary, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.weight(1f))
                if (timeText != null) Text(timeText, color = colors.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview ?: emptyText,
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    fontStyle = if (preview == null) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (unread > 99) "99+" else unread.toString(),
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(KaspaTeal)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** One line of a room's newest message: replies, voice notes and links read as what they are,
 *  never as their raw JSON or a tappable link (the same rules as the chat lists). */
private fun publicRoomPreview(content: String): String {
    com.kachat.app.util.MessageReply.parseOrNull(content)?.let { return "replied to \"${it.replyToPreview}\"" }
    com.kachat.app.util.InlineMediaSniff.mimeType(content)?.let { mime ->
        return when {
            mime.startsWith("audio/") -> "🎤 Audio message"
            mime.startsWith("image/") -> "📷 Photo"
            mime.startsWith("video/") -> "🎬 Video"
            else -> "📎 File"
        }
    }
    return com.kachat.app.util.NextcloudShareSniff.linkSafePreview(content)
}

/** A clock time today, "Yesterday", or the month and day, the way the chat lists read it. */
private fun publicRoomTime(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        com.kachat.app.util.ChatTimeFormat.isSameDay(ms, now) ->
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))
        com.kachat.app.util.ChatTimeFormat.isSameDay(ms, now - 24L * 60 * 60 * 1000) -> "Yesterday"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ms))
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
    // A room made here, rather than one of the curated indexed rooms: messages in it reach only
    // whoever is in it at the time, which the info button in the header says.
    val isCuratedRoom = channelName in com.kachat.app.models.FeaturedBroadcastChannels.INDEXED_NAMES
    var showOwnRoomExplainer by remember { mutableStateOf(false) }
    if (showOwnRoomExplainer) {
        ActionSheetContainer(
            title = "About this room",
            subtitle = null,
            onDismiss = { showOwnRoomExplainer = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "No one can see your messages in here unless they are also active in the room at the same time.",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 15.sp,
                )
                Text(
                    "If you want messages to persist and be seen by anyone who joins, you need to run your own indexer and have users add it to the room.",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 15.sp,
                )
            }
        }
    }

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
    // What was typed in this room last time comes back, as in 1:1 chats (iOS 360e5d2).
    DisposableEffect(channelName) {
        broadcastViewModel.openDraft(channelName)
        onDispose { broadcastViewModel.closeDraft(channelName) }
    }
    val estimatedFee by broadcastViewModel.estimatedFeeSompi.collectAsState()
    val senderProfiles by broadcastViewModel.senderProfiles.collectAsState()
    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()
    val replyingTo by broadcastViewModel.replyingTo.collectAsState()
    val kaspaExplorer by broadcastViewModel.kaspaExplorer.collectAsState()
    val networkFeeRate by broadcastViewModel.networkFeeRate.collectAsState()
    val feeRateOverride by broadcastViewModel.feeRateOverride.collectAsState()
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    // The sender whose avatar was tapped. One sheet serves every row - the parent presents it
    // for whichever sender was tapped, rather than a menu attached to each avatar in the list.
    var senderSheetTarget by remember { mutableStateOf<SenderSheetTarget?>(null) }
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
    val haptics = LocalHapticFeedback.current
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

    // Tapping into the composer scrolls to the newest message rather than letting the keyboard
    // rise over wherever you happened to be reading. You opened the composer to reply to the
    // room as it stands now.
    var composerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(composerFocused) {
        if (composerFocused && messages.isNotEmpty()) {
            // The keyboard is still animating up and the list has not been resized yet, so
            // scrolling immediately lands short of the bottom.
            kotlinx.coroutines.delay(120)
            listState.animateScrollToItem(messages.size - 1)
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
        // Opening a room is reading it. Marked again on the way out, so what arrived while it
        // was open does not come back as unread on the list behind it.
        broadcastViewModel.markRoomRead(channelName)
        onDispose {
            broadcastViewModel.markRoomRead(channelName)
            broadcastViewModel.stopLiveViewing()
            broadcastViewModel.stopIndexerBackfill()
        }
    }
    // Voice-note playback is owned per bubble; the room going away is what ends it.
    DisposableEffect(Unit) { onDispose { VoicePlayback.stopAllAfterChildrenDispose() } }
    val roomDotColorHex by androidx.hilt.navigation.compose.hiltViewModel<com.kachat.app.viewmodels.ConnectionViewModel>().dotColorHex.collectAsState()

    // Opened from a share link for a room the user isn't in: create/join it first so it lands in
    // "Your Channels" instead of vanishing the moment they navigate away. Curated rooms never
    // register a join request (they always exist), so they just open. joinChannel is idempotent
    // (INSERT IGNORE) and re-validates the name itself, so a stale request can't do damage.
    LaunchedEffect(channelName) {
        if (BroadcastDeepLink.consumeJoinRequest(channelName)) {
            broadcastViewModel.joinChannel(channelName)
        }
    }

    LaunchedEffect(myAddress) {
        myAddress?.let { broadcastViewModel.ensureSenderProfileFetched(it) }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Tapping the header row beside the name jumps to the very first message in
                    // the room. On the PARENT, so the room name (Room Info), the back button and
                    // the connection dot all keep their own taps - a child that handles the press
                    // consumes it and never reaches here. No ripple: this is the bar's empty
                    // space, not a button drawn on it.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        coroutineScope.launch { listState.scrollToItem(0) }
                    }
            ) {
            CenterAlignedTopAppBar(
                title = {
                    // The title itself is the way in to everything about the room - share,
                    // hidden users, its indexer, what is in it. It used to be two unlabelled
                    // toolbar glyphs with nowhere to put anything else.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { navController.navigate("broadcast_room_info/$channelName") }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("#$channelName", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Room info",
                            tint = LocalAppColors.current.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                                .clickable { ConnectionStatusOverlayState.open() },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(10.dp).background(Color(roomDotColorHex), CircleShape))
                        }
                    }
                },
                actions = {
                    // A room you made yourself: what "public" does and does not mean here. The
                    // curated rooms are indexed, so they need no such warning (iOS ac34790).
                    if (!isCuratedRoom) {
                        IconButton(onClick = { showOwnRoomExplainer = true }) {
                            Icon(Icons.Default.Info, contentDescription = "About this room", tint = KaspaTeal)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
            }
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
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { composerFocused = it.isFocused },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = LocalAppColors.current.textPrimary,
                                unfocusedTextColor = LocalAppColors.current.textPrimary,
                                focusedBorderColor = KaspaTeal,
                                unfocusedBorderColor = LocalAppColors.current.textSecondary
                            ),
                            // Load-bearing, same value as the 1:1 composer. Without a cap this
                            // field grows a line per line of text; the Scaffold measures its
                            // bottom bar against the screen height, so past ~a screenful the
                            // Column stopped growing and the newest lines - the ones with the
                            // caret - overflowed past its bottom edge and under the keyboard.
                            // Capped, the field scrolls internally and the caret stays put.
                            maxLines = 4
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
            senderSheetTarget?.let { target ->
                val address = target.address
                val sheetClipboard = LocalClipboardManager.current
                val sheetContext = LocalContext.current
                SenderActionsSheet(
                    address = address,
                    displayName = contactAliases[address] ?: senderKnsNames[address] ?: address.takeLast(10),
                    isOwnMessage = target.isOwnMessage,
                    onDismiss = { senderSheetTarget = null },
                    // Broadcast senders are usually strangers: openSenderProfile creates the
                    // contact row first, then hands back the address to navigate with.
                    onViewProfile = {
                        broadcastViewModel.openSenderProfile(address) { navController.navigate("chat_info/$it?fromBroadcast=true") }
                    },
                    onOpenChat = {
                        broadcastViewModel.openSenderProfile(address) { navController.navigate("chat/$it") }
                    },
                    onPayInKaspa = {
                        broadcastViewModel.openSenderProfile(address) { navController.navigate("chat/$it?paymentMode=true") }
                    },
                    onCopyAddress = {
                        sheetClipboard.setText(AnnotatedString(address))
                        com.kachat.app.util.showAddressCopiedToast(sheetContext, address)
                    },
                    // Per-room since 4.0: hides this sender in THIS room only.
                    onHide = { broadcastViewModel.hideSender(address, channelName) },
                    hideSubtitle = "Stop seeing their messages in this room. Undo it in Room Info.",
                )
            }
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
                    // An in-app KaChat link (shared KaPosts post / broadcast room invite) takes
                    // priority over the generic link path: it previews from local data only and
                    // opens in-app. Detected separately from TextLinkify because the kachat://
                    // form isn't a web URL at all and so is never linkified.
                    val internalLinkMatch = remember(displayContent, voiceContent) {
                        if (voiceContent == null && displayContent.length <= MESSAGE_TEXT_TRUNCATION_THRESHOLD) {
                            KaChatLink.findFirst(displayContent)
                        } else null
                    }
                    val isEntirelyInternalLinkMessage =
                        // The card is the WHOLE message wherever a KaChat link appears - see
                        // 1:1's identical rule. Copy and the full-text dialog keep everything.
                        internalLinkMatch != null
                    val messageReactions = reactionsByTxId[message.id] ?: emptyList()
                    var showMenu by remember { mutableStateOf(false) }
                    // Who reacted to this message, when the reader asks from the long-press menu.
                    var showReactions by remember { mutableStateOf(false) }
                    var showQuickReactionBar by remember { mutableStateOf(false) }
                    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
                    val clipboardManager = LocalClipboardManager.current
                    val menuContext = LocalContext.current

                    LaunchedEffect(message.senderAddress) {
                        broadcastViewModel.ensureSenderProfileFetched(message.senderAddress)
                    }

                    // The sender's avatar. Tapping it opens the sender half sheet presented by
                    // the screen (see SenderActionsSheet above the list); the row itself no
                    // longer owns a menu.
                    val avatar: @Composable () -> Unit = {
                        ContactAvatar(
                            imageUrl = senderProfiles[message.senderAddress],
                            fallbackText = message.senderAddress.takeLast(8),
                            size = 32.dp,
                            modifier = Modifier.clickable {
                                senderSheetTarget = SenderSheetTarget(message.senderAddress, isOwnMessage = isMine)
                            }
                        )
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
                                // graphicsLayer, not alpha(): reading the Animatable inside this block defers it
                                // to the draw phase, so dragging the row animates without recomposing every
                                // visible bubble on every frame. `.offset { }` below defers the same way.
                                .graphicsLayer { alpha = (-revealOffsetPx.value / maxRevealOffsetPx).coerceIn(0f, 1f) }
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
                                } else if (isEntirelyInternalLinkMessage) {
                                    // Nothing but an in-app KaChat link: the invite/post card IS
                                    // the message. Built locally, so no autoFetch gate applies.
                                    KaChatInternalLinkCard(
                                        ref = internalLinkMatch!!.ref,
                                        url = internalLinkMatch.raw,
                                        txId = message.id,
                                        kaspaExplorer = kaspaExplorer,
                                        onDoubleTap = { showQuickReactionBar = true }
                                    )
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
                                        isOutgoing = isMine,
                                        autoFetch = false
                                    )
                                } else {
                                    var textLayoutResult by remember(displayContent) { mutableStateOf<TextLayoutResult?>(null) }
                                    // Tapping a link here asks first rather than opening straight
                                    // away: a room's senders are anonymous, so opening one of
                                    // their links should be a decision, not a stray tap. iOS has
                                    // always required a deliberate gesture for the same reason.
                                    var tappedLinkUrl by remember { mutableStateOf<String?>(null) }
                                    tappedLinkUrl?.let { url ->
                                        LinkActionsSheet(
                                            url = url,
                                            onDismiss = { tappedLinkUrl = null },
                                            onOpen = {
                                                val internal = KaChatLink.parse(url)
                                                if (internal != null) openKaChatLink(internal)
                                                else uriHandler.openUri(url)
                                            },
                                            onCopy = {
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                                            },
                                        )
                                    }
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
                                            // kachat:// isn't a web URL, so TextLinkify never
                                            // sees it - style/annotate it here so it's tappable
                                            // inline too.
                                            KaChatLink.findFirst(displayContent)?.let { internal ->
                                                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), internal.range.first, internal.range.last + 1)
                                                addStringAnnotation("URL", internal.raw, internal.range.first, internal.range.last + 1)
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
                                                                .firstOrNull()?.let { annotation ->
                                                                    tappedLinkUrl = annotation.item
                                                                }
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
                                        // The pill on the bubble shows WHICH emoji; it has no
                                        // room to say how many or from whom. This does.
                                        if (messageReactions.isNotEmpty()) {
                                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                            PopupMenuRow(
                                                Icons.Default.Favorite,
                                                "Reactions (${messageReactions.size})"
                                            ) {
                                                showMenu = false
                                                showReactions = true
                                            }
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

                                if (showReactions) {
                                    ChatReactionsSheet(
                                        reactions = messageReactions,
                                        nameFor = { addr ->
                                            contactAliases[addr]
                                                ?: senderKnsNames[addr]
                                                ?: addr.takeLast(10)
                                        },
                                        isMe = { it == myAddress },
                                        // Rooms already fetch every sender's KNS profile for the
                                        // bubbles, so the faces are in hand.
                                        avatarFor = { senderProfiles[it] },
                                        onDismiss = { showReactions = false },
                                    )
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
                            // An internal link is always claimed above as the message itself,
                            // so only an external link can still want a card down here.
                            separateLinkPreviewUrl?.takeIf { internalLinkMatch == null }?.let { url ->
                                // Tap-to-load for all broadcast previews - see the entire-link
                                // branch above for why.
                                LinkPreviewCard(url = url, txId = message.id, kaspaExplorer = kaspaExplorer, onDoubleTap = { showQuickReactionBar = true }, isOutgoing = isMine, autoFetch = false)
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

