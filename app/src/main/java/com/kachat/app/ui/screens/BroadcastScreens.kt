package com.kachat.app.ui.screens

import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Translate
import com.kachat.app.util.UserFacingError
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.iosGlass
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
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
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
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import com.kachat.app.models.addressDisplayName
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.ChatRepository
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChatTimeFormat
import com.kachat.app.util.KaspaUnit
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
        if (name !in FeaturedBroadcastChannels.CURATED_NAMES) joinRequests.add(name)
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
    broadcastViewModel: BroadcastViewModel = hiltViewModel(),
    /** Select mode, owned by the Chats screen (its header and bottom bar act on the picked
     *  rooms, iOS e08c4cc): a tap picks a room instead of opening it. */
    isSelectionMode: Boolean = false,
    selectedRooms: Set<String> = emptySet(),
    onToggleRoom: (String) -> Unit = {},
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
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(joinState.status) {
        if (joinState.status == BroadcastViewModel.JoinChannelStatus.SUCCESS) {
            showJoinDialog = false
        }
    }

    // Don't leave the user stuck on a tab that just got hidden.

    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = LocalAppColors.current.background,
        // Runs on under the dock, as iOS's list runs on under its tab bar; it pads its own end.
        contentWindowInsets = WindowInsets(0),
        // Its own floating button for joining or creating a room (iOS 6639a0b) - only standalone
        // (Kaspa Hub). Inside Chats, the Chats screen's + covers it ("New Public Chat", in its New
        // sheet - iOS 5da8ccf / be0857a). Not while selecting.
        floatingActionButton = {
            if (embeddedInChats || isSelectionMode) return@Scaffold
            com.kachat.app.ui.theme.IosGlassFab(
                // Above the dock the list runs under.
                modifier = Modifier.padding(bottom = dockInset),
                onClick = {
                    channelInput = ""
                    broadcastViewModel.resetJoinChannelState()
                    showJoinDialog = true
                },
                icon = Icons.Default.AddComment,
                contentDescription = "Join or create a public room",
            )
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
            // Default rooms switched off in Public Chats settings stay out of the list entirely.
            val hiddenCurated by broadcastViewModel.hiddenCuratedRooms.collectAsState()
            val listed by broadcastViewModel.listedChannels.collectAsState()
            val joinedNames = channels.map { it.channelName }.toSet()
            val unjoinedLanguages = com.kachat.app.models.FeaturedBroadcastChannels.LANGUAGE_NAMES
                .filter { it !in joinedNames && it !in hiddenCurated }
            // the app's one rule (addressDisplayName): your name for them, else their .kachat
            // name on testnet (KNS elsewhere), else the short address (iOS e52357d)
            fun senderName(address: String): String =
                if (address == myAddressForRows) "You"
                else addressDisplayName(address, contactAliases[address], senderKnsNames[address])

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Room at the bottom so the last row never sits under the floating button, nor
                // the dock the list runs under.
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp + dockInset),
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
                        // In Select mode a tap picks the room, as on the Chats and Group Chats pages.
                        onClick = {
                            if (isSelectionMode) onToggleRoom(channel.channelName)
                            else navController.navigate("broadcast_channel/${channel.channelName}")
                        },
                        onLongClick = if (isSelectionMode) null else ({ roomActionTarget = channel.channelName }),
                        selected = if (isSelectionMode) channel.channelName in selectedRooms else null,
                    )
                }

                if (!isSelectionMode) items(unjoinedLanguages, key = { "language:$it" }) { name ->
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

                if (!isSelectionMode) item(key = "footer:note") {
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
    // Square tiles, three to a row, like every long-press menu (iOS cdac6d0).
    roomActionTarget?.let { name ->
        val channel = channels.firstOrNull { it.channelName == name }
        val isCurated = name in com.kachat.app.models.FeaturedBroadcastChannels.CURATED_NAMES
        // the indexer keeps it, so push covers it with the app closed (iOS 21c480d)
        val trackedRoomsVersion by com.kachat.app.services.IndexerTrackedRooms.changes.collectAsState()
        val isTracked = remember(name, trackedRoomsVersion) { com.kachat.app.models.FeaturedBroadcastChannels.isIndexerTracked(name) }
        val notifyOn = channel?.notifyEnabled == true
        val summaries by broadcastViewModel.roomSummaries.collectAsState()
        val hasUnread = (summaries[name]?.unreadCount ?: 0) > 0
        // iOS PublicChatListView's toast: up for 3 s.
        fun say(text: String) = IosToasts.show(text, durationMs = IosToastDuration.LONG)
        // iOS sizes this sheet for five tiles (two rows).
        TileActionSheet(title = "#$name", onDismiss = { roomActionTarget = null }, height = ActionSheetTileMetrics.sheetHeight(tiles = 5)) {
                if (hasUnread) {
                    ActionSheetRow(icon = Icons.Default.Drafts, title = stringResource(R.string.mark_as_read), subtitle = "Clears the unread badge on this room.") {
                        roomActionTarget = null
                        broadcastViewModel.markRoomRead(name)
                    }
                } else {
                    ActionSheetRow(icon = Icons.Default.MarkEmailUnread, title = stringResource(R.string.mark_as_unread), subtitle = "Puts the unread badge back so you come across it again.") {
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
                            isTracked -> "Notifies you of new messages, even when the app is closed."
                            else -> "Notifies you of new messages while the app is open."
                        },
                    ) {
                        roomActionTarget = null
                        broadcastViewModel.setNotifyEnabled(name, !notifyOn)
                        // iOS toggleNotify's words.
                        say(
                            when {
                                notifyOn -> "Notifications are off for this public chat"
                                isTracked -> "You'll get notifications for new messages in this public chat, even when the app is closed"
                                else -> "You'll get a notification for new messages in this public chat as long as your app remains open"
                            }
                        )
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
                        title = stringResource(R.string.delete),
                        subtitle = "Removes this room and its messages from this device.",
                        tint = LocalAppColors.current.danger,
                    ) {
                        roomActionTarget = null
                        channelToLeave = name
                    }
                } else if (isCurated) {
                    // A default room is never really deleted - it is switched off, exactly like its
                    // toggle in Public Chats settings, and that toggle brings it back. Nothing is
                    // lost, so it needs no confirmation (iOS e08c4cc).
                    val offText = stringResource(R.string.default_room_switched_off, name)
                    ActionSheetRow(
                        icon = Icons.Default.Delete,
                        title = stringResource(R.string.delete),
                        subtitle = stringResource(R.string.default_room_delete_subtitle),
                        tint = LocalAppColors.current.danger,
                    ) {
                        roomActionTarget = null
                        broadcastViewModel.removeFromList(name)
                        say(offText)
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
            com.kachat.app.ui.theme.IosTextField(
                value = channelInput,
                onValueChange = { channelInput = it },
                placeholder = { Text(stringResource(R.string.channel_name), color = LocalAppColors.current.textTertiary) },
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
                    color = LocalAppColors.current.danger,
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

    // Deleting a room asks on a sheet, like every other menu here (iOS eff5c09).
    channelToLeave?.let { channelName ->
        ActionSheetContainer(
            title = "Delete #$channelName?",
            subtitle = stringResource(R.string.leaving_this_broadcast_permanently_deletes_every),
            onDismiss = { channelToLeave = null },
        ) {
            ActionSheetRow(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.delete),
                subtitle = "Removes this room and its messages from this device.",
                tint = LocalAppColors.current.danger,
            ) {
                broadcastViewModel.leaveChannel(channelName)
                channelToLeave = null
            }
            ActionSheetRow(
                icon = Icons.Default.Close,
                title = "Keep",
                subtitle = "Leave the room as it is.",
            ) { channelToLeave = null }
        }
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
    val joinedChannels by broadcastViewModel.joinedChannels.collectAsState()
    val joinedNames = remember(joinedChannels) { joinedChannels.map { it.channelName }.toSet() }
    // iOS presents it as a full-height sheet holding a Form (PublicChatsSettingsView): an inline
    // bar with the title and Done on glass, each section's rooms on an inset-grouped card with
    // inset separators, the headers in the iOS 26 Form style and the footer under its card.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        IosSheetPage(grouped = true) {
            val colors = LocalAppColors.current
            SheetNavBar(
                title = stringResource(R.string.broadcasts),
                trailing = { SheetBarButton(stringResource(R.string.done), onClick = onDismiss) },
            )

            @Composable
            fun roomSwitch(name: String, showDivider: Boolean) {
                val language = com.kachat.app.models.FeaturedBroadcastChannels.languageDisplayName(name)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("#$name", color = colors.textPrimary, fontSize = 17.sp)
                        if (language != null) Text(language, color = colors.textSecondary, fontSize = 12.sp)
                    }
                    com.kachat.app.ui.theme.IosSwitch(
                        // On only when the room really is in the Chats circles: joined and not
                        // switched off. A language room never opened reads off until switched on.
                        checked = name !in hidden && name in joinedNames,
                        onCheckedChange = { shown -> broadcastViewModel.setCuratedRoomShown(name, shown) }
                    )
                }
                if (showDivider) SettingsDivider()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column {
                    SettingsSection(title = "Popular") {
                        val names = com.kachat.app.models.FeaturedBroadcastChannels.NAMES
                        names.forEachIndexed { i, name -> roomSwitch(name, showDivider = i < names.size - 1) }
                    }
                    Text(
                        "A room that is switched off no longer appears in Public Chats and never sends a notification. Switch it back on at any time.",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    )
                }
                SettingsSection(title = "Other Languages") {
                    val names = com.kachat.app.models.FeaturedBroadcastChannels.LANGUAGE_NAMES
                    names.forEachIndexed { i, name -> roomSwitch(name, showDivider = i < names.size - 1) }
                }
                Spacer(Modifier.navigationBarsPadding().height(24.dp))
            }
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
    /** Non-null in Select mode: whether this room is picked. */
    selected: Boolean? = null,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) KaspaTeal else LocalAppColors.current.textSecondary,
                modifier = Modifier.padding(end = 12.dp).size(24.dp)
            )
        }
        Box(
            modifier = Modifier.size(50.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("#", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 24.sp, fontFamily = IosRoundedFontFamily)
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
                    // The same badge the Chats and Group Chats rows carry: a teal circle with the
                    // count in white, the number itself uncapped (only the tab badges say 99+) -
                    // iOS draws all three lists' badges from one piece of code.
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                            .background(KaspaTeal, CircleShape)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            unread.toString(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
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
    // A room the indexer tracks (the curated ones, or one added on the indexer): history comes
    // from the public chat indexer and retention is fixed at 30 days. Any other room reaches only
    // whoever is in it at the time, which the info button in the header says (iOS 21c480d).
    val trackedRoomsVersion by com.kachat.app.services.IndexerTrackedRooms.changes.collectAsState()
    val isIndexedRoom = remember(channelName, trackedRoomsVersion) {
        com.kachat.app.models.FeaturedBroadcastChannels.isIndexerTracked(channelName)
    }
    var showOwnRoomExplainer by remember { mutableStateOf(false) }
    /** The bell in the top-right corner: this room's notifications, on a sheet. */
    var showNotifySheet by remember { mutableStateOf(false) }
    val joinedRooms by broadcastViewModel.joinedChannels.collectAsState()
    val roomNotifyEnabled = joinedRooms.firstOrNull { it.channelName == channelName }?.notifyEnabled == true
    // The bell's sheet: what notifications do in this room, and the switch (iOS eff5c09).
    if (showNotifySheet) {
        ActionSheetContainer(
            title = "#$channelName",
            subtitle = if (roomNotifyEnabled) {
                if (isIndexedRoom) "You're notified of new messages here, even when the app is closed."
                else "You're notified of new messages here while the app is open."
            } else {
                "New messages here don't notify you. The room still updates when you open it."
            },
            onDismiss = { showNotifySheet = false },
        ) {
            ActionSheetRow(
                icon = if (roomNotifyEnabled) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                title = if (roomNotifyEnabled) "Turn Off Notifications" else "Turn On Notifications",
                subtitle = if (roomNotifyEnabled) {
                    "No notification for new messages in this room."
                } else if (isIndexedRoom) {
                    "Notifies you of new messages, even when the app is closed."
                } else {
                    "Notifies you of new messages while the app is open."
                },
            ) {
                broadcastViewModel.setNotifyEnabled(channelName, !roomNotifyEnabled)
                showNotifySheet = false
            }
        }
    }
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
    val messageWindow by broadcastViewModel.getMessageWindow(channelName)
        .collectAsState(initial = com.kachat.app.repository.BroadcastRepository.RoomWindow(emptyList(), false))
    val messages = messageWindow.messages
    // Reactions aggregated per message txId — same shape as GroupChatScreen's groupReactionsByTxId.
    val channelReactions by broadcastViewModel.getReactions(channelName).collectAsState(initial = emptyList())
    // Translate from the long-press menu (iOS 03e5128) - read once here, not per row.
    val translationStates by broadcastViewModel.translations.collectAsState()
    val translationOriginals by broadcastViewModel.showingOriginal.collectAsState()
    val translatableKeys by broadcastViewModel.translatable.collectAsState()
    LaunchedEffect(Unit) { broadcastViewModel.refreshTranslationLanguages() }
    val reactionsByTxId = remember(channelReactions) { channelReactions.groupBy { it.targetTxId } }
    val quickReactionEmojis by settingsViewModel.quickReactionEmojis.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    // Zero-balance funding gate — same behavior as the 1:1/group chat threads (confirmed 0 KAS
    // only, on-entry refresh + 10s re-poll while gated); see ZeroBalanceFundingUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()
    val sendState by broadcastViewModel.sendBroadcastState.collectAsState()
    val voiceRecordingState by broadcastViewModel.voiceRecordingState.collectAsState()
    // The composer's text, kept as the State itself and read only in the text field (inside
    // ComposerTextScope), in lambdas that run on a tap, and through messageTextIsEmpty - so a
    // keystroke recomposes just the field, not the composer around it (iOS 0977a5b).
    val messageTextState = broadcastViewModel.messageText.collectAsState()
    val messageTextIsEmpty by remember(messageTextState) { derivedStateOf { messageTextState.value.isEmpty() } }
    // What was typed in this room last time comes back, as in 1:1 chats (iOS 360e5d2).
    DisposableEffect(channelName) {
        broadcastViewModel.openDraft(channelName)
        onDispose { broadcastViewModel.closeDraft(channelName) }
    }
    val estimatedFee by broadcastViewModel.estimatedFeeSompi.collectAsState()
    val isEstimatingFee by broadcastViewModel.isEstimatingFee.collectAsState()
    val senderProfiles by broadcastViewModel.senderProfiles.collectAsState()
    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()
    val replyingTo by broadcastViewModel.replyingTo.collectAsState()
    // The newest edit per message (see MessageEdit), applied as each bubble is drawn - the
    // envelope itself is never a message row.
    val editsByTxId by broadcastViewModel.getEdits(channelName).collectAsState(initial = emptyMap())
    val editingMessage by broadcastViewModel.editingMessage.collectAsState()
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
        // The shown fee may lag the text by a pause (see TypingFeeGate): price the exact text
        // now, so the rate Save derives from the fee matches what is being sent.
        broadcastViewModel.refreshFeePreview()
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

    val micContext = LocalContext.current
    // With a Nextcloud server linked, the mic asks on chain or via Nextcloud, mirroring the
    // 1:1/group "+" sheet (iOS 8b13460): via Nextcloud, the recording uploads to the server and
    // the room only carries the share link. The choice has to survive the permission prompt.
    val nextcloudAccount by broadcastViewModel.nextcloudAccount.collectAsState()
    var showVoiceRouteSheet by remember { mutableStateOf(false) }
    var voiceViaNextcloud by remember { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) broadcastViewModel.startVoiceRecording(channelName, viaNextcloud = voiceViaNextcloud)
    }
    val startVoiceRecordingIfPermitted = { viaNextcloud: Boolean ->
        voiceViaNextcloud = viaNextcloud
        if (broadcastViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                broadcastViewModel.startVoiceRecording(channelName, viaNextcloud = viaNextcloud)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    val startVoiceMessage = {
        if (nextcloudAccount != null) showVoiceRouteSheet = true else startVoiceRecordingIfPermitted(false)
    }

    // First fill JUMPS to the newest message instantly - featured rooms hold the whole 30-day
    // window, and the old unconditional animateScrollToItem crawled the full history on every
    // open. After that, follow new arrivals only when the reader is already at the bottom, so
    // indexer backfill and live inserts never yank someone reading history (same policy as 1:1
    // chats). Keyed on the NEWEST message, not the count: each backfill page of OLDER history
    // grew the count and ran this again, so a filling room animated to the bottom once per page
    // (iOS 9c9e3e0).
    var hasPositionedAtLatest by remember(channelName) { mutableStateOf(false) }
    LaunchedEffect(channelName, messages.lastOrNull()?.id) {
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
    // always-listen — bounded to exactly as long as this composable is on screen. Every room
    // additionally backfills from its broadcast indexer (once + every 8s) so history sent while
    // the app was closed shows up too.
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
            // iOS 26's room header (PublicChatChannelView): no bar of its own - Back and the
            // connection dot each on a glass disc leading, the bell on glass trailing, and the
            // room's "#" circle over a "#name >" capsule in the middle, all floating over the
            // messages, which scroll on underneath and fade out under the status bar. The list
            // takes this header's height as its top content padding, not as a clip.
            val headerTapBand = remember { ChatHeaderTapBandState() }
            val headerBackground = LocalAppColors.current.background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to headerBackground.copy(alpha = 0.95f),
                            0.55f to headerBackground.copy(alpha = 0.7f),
                            1f to Color.Transparent,
                        )
                    )
                    // Icon and name open Room Info - and so does the 24dp either side of them;
                    // the space further out jumps to the very first message (iOS 1d5a555). The
                    // back button, the connection dot and the bell keep their own taps.
                    .chatHeaderTapBand(
                        headerTapBand,
                        onChip = { navController.navigate("broadcast_room_info/$channelName") },
                        onBand = { coroutineScope.launch { listState.scrollToItem(0) } },
                    )
            ) {
                // The chat thread's bar (ChatNavBar), with the room's connection dot beside Back
                // as iOS's navigationBarLeading ConnectionStatusIndicator puts it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetBarBackButton(onClick = onBack)
                    Spacer(Modifier.width(8.dp))
                    ConnectionDotButton(onClick = { ConnectionStatusOverlayState.open() })
                    Spacer(Modifier.weight(1f))
                    // A room you made yourself: what "public" does and does not mean here. Rooms
                    // the indexer tracks keep their history there, so they need no such warning
                    // (iOS ac34790, 21c480d). iOS 26 puts neighbouring trailing items on one
                    // piece of glass, a lone bell on its own disc.
                    ToolbarGlassGroup {
                        if (!isIndexedRoom) {
                            ToolbarIconButton(Icons.Outlined.Info, "About this room", onClick = { showOwnRoomExplainer = true })
                        }
                        // This room's notifications, one tap away - the same switch the Public
                        // Chats list offers in its room menu (iOS eff5c09).
                        ToolbarIconButton(
                            if (roomNotifyEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                            if (roomNotifyEnabled) "Notifications on" else "Notifications off",
                            onClick = { showNotifySheet = true },
                        )
                    }
                }
                // iOS PublicChatRoomTitleChip: the 1:1/group header chip measure for measure, the
                // circle carrying the room glyph (accent at 20%, "number" 18 semibold) where they
                // carry a photo. The title itself is the way in to everything about the room.
                ChatHeaderChipLayout(
                    name = "#$channelName",
                    onClick = { navController.navigate("broadcast_room_info/$channelName") },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .chatHeaderChip(headerTapBand)
                        .statusBarsPadding(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(LocalAppColors.current.background)
                            .background(KaspaTeal.copy(alpha = 0.2f)),
                    ) {
                        Text("#", color = KaspaTeal, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        bottomBar = {
            // iOS: the compose bar floats over the list as a bottom safe-area inset - the
            // messages run on underneath it - with the zero-balance card right above it (padded
            // 16 either side, 4 below), never over the room. Only the composer under the card
            // dims and goes inert (Modifier.zeroBalanceComposerGate in ZeroBalanceFundingUi.kt).
            //
            // With the dock showing, the messages run on under it too and the composer rests just
            // above it (LocalDockInset, which already holds the system bar under the dock); with
            // the keyboard up the dock is behind it, the inset is 0 and the composer sits on the
            // keyboard as before.
            //
            // One fixed modifier chain: the bottom padding and imePadding() never come or go.
            // Swapping navigationBarsPadding() in and out ahead of imePadding() when the keyboard
            // opened froze the app (an endless consumed-insets loop - an ANR seen while typing in
            // a room). The navigation bar needs nothing of its own: without the keyboard the dock
            // inset already holds it, and with the keyboard up the IME inset covers it.
            val composerBackground = LocalAppColors.current.background
            val dockInset = LocalDockInset.current
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to composerBackground.copy(alpha = 0.85f),
                        )
                    )
                    .padding(bottom = dockInset)
                    .imePadding()
                    .padding(bottom = 2.dp)
            ) {
            if (fundingGate.active) {
                ZeroBalanceFundingCard(
                    walletAddress = fundingGate.chattingAddress,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            // iOS composeBar: VStack(spacing: 8), padded 16 by 8.
            Column(
                modifier = Modifier
                    .zeroBalanceComposerGate(fundingGate.active)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sendState.status == BroadcastViewModel.SendBroadcastStatus.FAILED) {
                    Text(
                        sendState.message ?: "Failed to send",
                        color = LocalAppColors.current.danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                // Editing and replying are mutually exclusive - the banner says which one is in
                // progress (iOS editBanner / replyBanner: caption lines on secondarySystemBackground
                // in a 12-rounded box padded 12 by 8, inset a further 16 and 8 below the top).
                editingMessage?.let { editing ->
                    RoomComposerBanner(
                        icon = Icons.Default.Edit,
                        title = "Editing message",
                        preview = editsByTxId[editing.id]?.text
                            ?: com.kachat.app.util.MessageEdit.unwrappedText(editing.content),
                        cancelDescription = "Cancel editing",
                        onCancel = {
                            broadcastViewModel.cancelEditing()
                            broadcastViewModel.setMessageText("")
                        },
                    )
                }
                if (editingMessage == null) replyingTo?.let { reply ->
                    RoomComposerBanner(
                        icon = Icons.AutoMirrored.Filled.Reply,
                        title = "Replying to ${addressDisplayName(reply.senderAddress, contactAliases[reply.senderAddress], senderKnsNames[reply.senderAddress])}",
                        preview = VoiceMessage.parseOrNull(reply.content)?.let { "🎤 Audio message" }
                            ?: MessageReply.parseOrNull(reply.content)?.text
                            ?: reply.content,
                        cancelDescription = stringResource(R.string.cancel_reply),
                        onCancel = { broadcastViewModel.cancelReply() },
                    )
                }
                if (voiceRecordingState.status == BroadcastViewModel.VoiceRecordingStatus.RECORDING) {
                    if (showFeeEstimate && estimatedFee != null) {
                        ComposerFeePill(
                            feeSompi = estimatedFee,
                            estimating = false,
                            onTap = { openFeeEditor(it) },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    // iOS recordingBar: trash, a small red mic and "Recording..." on the field's
                    // glass, and the send glyph on its own 36 glass square, 12 apart.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .iosGlass(cornerRadius = 20.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.cancel_recording),
                                tint = LocalAppColors.current.danger,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable { broadcastViewModel.cancelVoiceRecording() },
                            )
                            Icon(Icons.Default.Mic, contentDescription = null, tint = LocalAppColors.current.danger, modifier = Modifier.size(14.dp))
                            Text(
                                text = "Recording... ${formatRecordingElapsed(voiceRecordingState.elapsedMs)}",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 17.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        RoomComposerSquareButton(
                            contentDescription = stringResource(R.string.send),
                            onClick = { broadcastViewModel.stopAndSendVoiceRecording(channelName) },
                        ) {
                            RoomSendGlyph(enabled = true)
                        }
                    }
                } else {
                    if (showFeeEstimate && !messageTextIsEmpty && (estimatedFee != null || isEstimatingFee)) {
                        // iOS feeBubble: the fee, or - while a re-price waits for its pause - the shimmering
                        // placeholder (TypingFeeGate).
                        ComposerFeePill(
                            feeSompi = estimatedFee,
                            estimating = isEstimatingFee,
                            onTap = { openFeeEditor(it) },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    // iOS: the field on glassBackground(cornerRadius: 20) - a capsule at one line -
                    // and the mic or send on its own 36 x 36 glassBackground(cornerRadius: 12),
                    // 12 apart.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // The one place the text itself is read while composing - see
                        // messageTextState.
                        ComposerTextScope {
                            val composerText = messageTextState.value
                            androidx.compose.foundation.text.BasicTextField(
                                value = composerText,
                                onValueChange = {
                                    // Someone's payment request pasted in opens Send Kaspa instead of
                                    // landing in the message (iOS c6851d6).
                                    if (!PaymentRequestPaste.interceptInsert(messageTextState.value, it)) {
                                        broadcastViewModel.setMessageText(it, fromTyping = true)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .chatComposerFieldModifier()
                                    .onFocusChanged { composerFocused = it.isFocused },
                                textStyle = chatComposerTextStyle(),
                                cursorBrush = SolidColor(KaspaTeal),
                                // Load-bearing, same value as the 1:1 composer. Without a cap this
                                // field grows a line per line of text; the Scaffold measures its
                                // bottom bar against the screen height, so past ~a screenful the
                                // Column stopped growing and the newest lines - the ones with the
                                // caret - overflowed past its bottom edge and under the keyboard.
                                // Capped, the field scrolls internally and the caret stays put.
                                maxLines = 4,
                                decorationBox = { inner ->
                                    RoomComposerFieldDecoration(
                                        placeholder = "Message #$channelName",
                                        showPlaceholder = composerText.isEmpty(),
                                        innerTextField = inner,
                                    )
                                },
                            )
                        }
                        val sending = sendState.status == BroadcastViewModel.SendBroadcastStatus.SENDING
                        if (messageTextIsEmpty) {
                            // iOS: mic.fill at title3 in the accent colour.
                            RoomComposerSquareButton(
                                contentDescription = "Record voice message",
                                onClick = { startVoiceMessage() },
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                            }
                            if (showVoiceRouteSheet) {
                                ComposerMediaRouteSheet(
                                    kind = ComposerMediaKind.VOICE,
                                    onChoose = { viaNextcloud ->
                                        showVoiceRouteSheet = false
                                        startVoiceRecordingIfPermitted(viaNextcloud)
                                    },
                                    onBack = { showVoiceRouteSheet = false },
                                    onDismiss = { showVoiceRouteSheet = false },
                                )
                            }
                        } else {
                            // iOS: arrow.up.circle.fill at title3, the accent colour - secondary
                            // while a send is in flight.
                            RoomComposerSquareButton(
                                contentDescription = "Send",
                                enabled = !sending,
                                onClick = {
                                    val messageText = messageTextState.value
                                    if (!sending && messageText.isNotBlank()) {
                                        // Editing: the composer's text replaces the message being
                                        // edited - one edit broadcast, no new bubble.
                                        val editing = editingMessage
                                        if (editing != null) {
                                            broadcastViewModel.sendEdit(channelName, editing.id, messageText)
                                        } else {
                                            broadcastViewModel.sendBroadcast(channelName, messageText)
                                        }
                                        broadcastViewModel.setMessageText("")
                                    }
                                },
                            ) {
                                RoomSendGlyph(enabled = !sending)
                            }
                        }
                    }
                }
            }
            }
        }
    ) { padding ->
        // Full height, NOT padded by the header and composer: the messages scroll on underneath
        // both (iOS), and the list takes their heights as content padding instead.
        Column(modifier = Modifier.fillMaxSize()) {
        // The in-room retention banner was removed to keep the chat clean; the retention note
        // now lives next to the "Popular" header on the broadcast list (matches iOS).
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
        if (messages.isEmpty()) {
            // iOS emptyState: the radio-waves glyph at 48 in secondary, "No messages yet" in
            // headline and the invitation in subheadline secondary, 16 apart, centred.
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    Icons.Default.Sensors,
                    contentDescription = null,
                    tint = LocalAppColors.current.textSecondary,
                    modifier = Modifier.size(52.dp),
                )
                Text(
                    "No messages yet",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Be the first to post in #$channelName.",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            senderSheetTarget?.let { target ->
                val address = target.address
                val sheetClipboard = LocalClipboardManager.current
                val sheetContext = LocalContext.current
                SenderActionsSheet(
                    address = address,
                    displayName = addressDisplayName(address, contactAliases[address], senderKnsNames[address]),
                    isOwnMessage = target.isOwnMessage,
                    onDismiss = { senderSheetTarget = null },
                    // Broadcast senders are usually strangers: openSenderProfile creates the
                    // contact row first, then hands back the address to navigate with.
                    onViewProfile = {
                        broadcastViewModel.openSenderProfile(address) { navController.navigate("chat_info/$it") }
                    },
                    onOpenChat = {
                        broadcastViewModel.openSenderProfile(address) { navController.navigate("chat/$it") }
                    },
                    onPayInKaspa = {
                        broadcastViewModel.openSenderProfile(address) { navController.navigate("chat/$it?paymentMode=true") }
                    },
                    onCopyAddress = {
                        sheetClipboard.setText(AnnotatedString(address))
                        com.kachat.app.util.showAddressCopiedToast(sheetContext, address, haptic = false)
                    },
                    // Per-room since 4.0: hides this sender in THIS room only.
                    onHide = { broadcastViewModel.hideSender(address, channelName) },
                    hideSubtitle = "Stop seeing their messages in this room. Undo it in Room Info.",
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().closesQuickReactionBar().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // iOS: the LazyVStack padded 8 top and bottom, inside the header's and the
                // compose bar's insets.
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                )
            ) {
                // At the top of the room, above the oldest message the window reaches, exactly
                // where iOS puts it (02a4f58).
                if (messageWindow.hasMore) {
                    item(key = "load_earlier") {
                        // iOS: a plain button - caption semibold in the accent colour, the full
                        // width, 8 above and below.
                        Text(
                            stringResource(R.string.load_earlier_messages),
                            color = KaspaTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { broadcastViewModel.loadEarlier(channelName) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val showDateDivider = index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, message.blockTimestamp)
                    if (showDateDivider) {
                        // iOS dateDivider: caption2 semibold in secondary, padded 12 by 4 on
                        // secondarySystemBackground in a 12-rounded box, 8 above and below.
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                ChatTimeFormat.formatDateDivider(message.blockTimestamp, stringResource(R.string.chat_day_today), stringResource(R.string.chat_day_yesterday)),
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(roomSecondarySystemBackground())
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                        // The divider sits in the same list row as the message under it; iOS
                        // spaces them 8 apart in its stack.
                        Spacer(Modifier.height(8.dp))
                    }
                    // An edited message reads with its newest text; the stored row is untouched.
                    val editForRow = editsByTxId[message.id]
                    @Suppress("NAME_SHADOWING")
                    val message = remember(message, editForRow) {
                        if (editForRow == null) message
                        else message.copy(content = com.kachat.app.util.MessageEdit.apply(editForRow.text, message.content))
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
                    // Worth considering for Translate: someone else's plain text. Voice notes, KaChat
                    // link cards and structured payloads have nothing to translate.
                    val translationKey = remember(message.id, displayContent) {
                        broadcastViewModel.translationKey(message.id, displayContent)
                    }
                    val considersTranslation = !isMine && voiceContent == null &&
                        !displayContent.trimStart().startsWith("{") && internalLinkMatch == null
                    LaunchedEffect(translationKey, considersTranslation) {
                        if (considersTranslation) broadcastViewModel.considerTranslation(translationKey, displayContent)
                    }
                    val translationState = translationStates[translationKey]
                    // The text the bubble draws: the translation while one is showing, otherwise the
                    // message. Everything that decides WHAT the message is still reads displayContent.
                    val shownText = (translationState as? com.kachat.app.services.PostTranslationService.TranslationState.Translated)
                        ?.takeIf { translationKey !in translationOriginals }?.text ?: displayContent
                    val isEntirelyInternalLinkMessage =
                        // The card is the WHOLE message wherever a KaChat link appears - see
                        // 1:1's identical rule. Copy and the full-text dialog keep everything.
                        internalLinkMatch != null
                    val messageReactions = reactionsByTxId[message.id] ?: emptyList()
                    var showMenu by remember { mutableStateOf(false) }
                    // Who reacted to this message, when the reader asks from the long-press menu.
                    var showReactions by remember { mutableStateOf(false) }
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
                            address = message.senderAddress,
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
                        // iOS PublicChatMessageRow: HStack(alignment: .bottom, spacing: 8) - the 32
                        // avatar on the sender's side, at least 40 left free on the other, and a
                        // VStack spacing 3 of name, quote, bubble, extras and the time line.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Bottom
                        ) {
                        if (!isMine) {
                            avatar()
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.width(40.dp))
                        }
                        Column(
                            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .onGloballyPositioned { coords ->
                                    menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
                                }
                        ) {
                            // The double-tap bar sits directly above the message, as iOS's does.
                            InlineQuickReactionBar(
                                messageId = message.id,
                                onReact = { emoji ->
                                    // Tapping your active emoji removes it; any other emoji
                                    // adds/replaces — same toggle rule as 1:1/group chats.
                                    val existing = messageReactions.firstOrNull { it.reactorAddress == myAddress }
                                    val action = if (existing?.emoji == emoji) "remove" else "add"
                                    broadcastViewModel.sendReaction(channelName, message.id, emoji, action)
                                },
                                onReply = { broadcastViewModel.startReplyTo(message) },
                                emojis = quickReactionEmojis,
                            )
                            // iOS: caption semibold in the accent colour, flush with the bubble's
                            // edge - "You" over your own messages.
                            Text(
                                if (isMine) stringResource(R.string.reactions_you)
                                else addressDisplayName(message.senderAddress, contactAliases[message.senderAddress], senderKnsNames[message.senderAddress]),
                                color = KaspaTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (replyContent != null) {
                                // iOS replyQuoteView: caption2 semibold sender over a two-line
                                // caption preview, 2 apart, padded 8 on tertiarySystemBackground in
                                // a 10-rounded box at most 240 wide.
                                Column(
                                    horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier
                                        .widthIn(max = 240.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(roomTertiarySystemBackground())
                                        .clickable { jumpToReply(replyContent.replyToId) }
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        addressDisplayName(replyContent.replyToSender, contactAliases[replyContent.replyToSender], senderKnsNames[replyContent.replyToSender]),
                                        color = KaspaTeal,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        replyContent.replyToPreview,
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = if (isMine) TextAlign.End else TextAlign.Start,
                                    )
                                }
                            }
                            Box {
                                if (voiceContent != null) {
                                    AudioBubble(
                                        voiceContent,
                                        isSent = isMine,
                                        onLongPress = { showMenu = true },
                                        onDoubleClick = { QuickReactionBarState.activeId = message.id }
                                    )
                                } else if (displayContent.length > MESSAGE_TEXT_TRUNCATION_THRESHOLD) {
                                    // See MESSAGE_TEXT_TRUNCATION_THRESHOLD's doc comment in Screens.kt -
                                    // broadcast rooms are public/unencrypted, so a huge wall of text (e.g.
                                    // stray base64) landing here is if anything more likely than in a
                                    // private chat.
                                    var showFullText by remember { mutableStateOf(false) }
                                    Column(
                                        modifier = Modifier
                                            .widthIn(max = 280.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(roomBubbleColor(isMine))
                                            .combinedClickable(
                                                onClick = { showFullText = true },
                                                onLongClick = { showMenu = true },
                                                onDoubleClick = { QuickReactionBarState.activeId = message.id }
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            displayContent.take(MESSAGE_TEXT_PREVIEW_LENGTH) + "…",
                                            color = if (isMine) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textPrimary,
                                            fontSize = 17.sp,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.show_more),
                                            color = if (isMine) LocalAppColors.current.onOutgoingBubble.copy(alpha = 0.85f) else KaspaTeal,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    if (showFullText) {
                                        FullMessageTextDialog(
                                            text = displayContent,
                                            onDismiss = { showFullText = false },
                                            // iOS PublicChatChannelView's onCopyMessage: "Message copied." for 3 s.
                                            onCopy = {
                                                clipboardManager.setText(AnnotatedString(displayContent))
                                                IosToasts.show("Message copied.", durationMs = IosToastDuration.LONG)
                                            }
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
                                        onDoubleTap = { QuickReactionBarState.activeId = message.id }
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
                                        onDoubleTap = { QuickReactionBarState.activeId = message.id },
                                        isOutgoing = isMine,
                                        autoFetch = false
                                    )
                                } else {
                                    var textLayoutResult by remember(shownText) { mutableStateOf<TextLayoutResult?>(null) }
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
                                    val linkColor = if (isMine) LocalAppColors.current.onOutgoingBubble else KaspaTeal
                                    val annotatedBody = remember(shownText, isMine) {
                                        buildAnnotatedString {
                                            append(shownText)
                                            for (match in TextLinkify.findUrls(shownText)) {
                                                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                                                addStringAnnotation("URL", match.uri, match.range.first, match.range.last + 1)
                                            }
                                            // kachat:// isn't a web URL, so TextLinkify never
                                            // sees it - style/annotate it here so it's tappable
                                            // inline too.
                                            KaChatLink.findFirst(shownText)?.let { internal ->
                                                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), internal.range.first, internal.range.last + 1)
                                                addStringAnnotation("URL", internal.raw, internal.range.first, internal.range.last + 1)
                                            }
                                        }
                                    }
                                    // iOS: body text padded 14 by 10 on secondarySystemBackground (the
                                    // outgoing teal for your own) in a 16-rounded bubble, 280 at most.
                                    Column(
                                        modifier = Modifier
                                            .widthIn(max = 280.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(roomBubbleColor(isMine))
                                    ) {
                                        Text(
                                            annotatedBody,
                                            color = if (isMine) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textPrimary,
                                            fontSize = 17.sp,
                                            modifier = Modifier
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                                .pointerInput(annotatedBody) {
                                                    detectTapGestures(
                                                        onLongPress = { showMenu = true },
                                                        onDoubleTap = { QuickReactionBarState.activeId = message.id },
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
                                    // The app's half sheet, as everywhere else (iOS 1bc6ba6).
                                    MessageActionsSheet(
                                        title = if (voiceContent != null) "Voice Message" else "Message",
                                        preview = displayContent.takeIf { voiceContent == null },
                                        onDismiss = { showMenu = false },
                                    ) {
                                        ActionSheetRow(
                                            icon = Icons.AutoMirrored.Filled.Reply,
                                            title = stringResource(R.string.reply),
                                            subtitle = "Quotes this message above your reply.",
                                        ) {
                                            broadcastViewModel.startReplyTo(message)
                                            showMenu = false
                                        }
                                        // Your own delivered text messages only - the same gate
                                        // 1:1 and group chats use.
                                        if (isMine && message.deliveryStatus == "sent" &&
                                            com.kachat.app.util.MessageEdit.isEditable(message.content)) {
                                            ActionSheetRow(
                                                icon = Icons.Default.Edit,
                                                title = "Edit",
                                                subtitle = "Changes the text. Everyone sees the new version.",
                                            ) {
                                                broadcastViewModel.startEditing(message)
                                                broadcastViewModel.setMessageText(
                                                    com.kachat.app.util.MessageEdit.unwrappedText(message.content)
                                                )
                                                showMenu = false
                                            }
                                        }
                                        ActionSheetRow(
                                            icon = Icons.Default.ContentCopy,
                                            title = stringResource(R.string.copy_message),
                                            subtitle = "Copies the text to your clipboard.",
                                        ) {
                                            clipboardManager.setText(AnnotatedString(displayContent))
                                            showMenu = false
                                            // iOS PublicChatChannelView: "Message copied." for 3 s.
                                            IosToasts.show("Message copied.", durationMs = IosToastDuration.LONG)
                                        }
                                        when (translationState) {
                                            is com.kachat.app.services.PostTranslationService.TranslationState.Translated ->
                                                if (translationKey in translationOriginals) {
                                                    ActionSheetRow(
                                                        icon = Icons.Default.Translate,
                                                        title = stringResource(R.string.show_translation),
                                                        subtitle = stringResource(R.string.translation_show_translation_subtitle),
                                                    ) {
                                                        broadcastViewModel.showTranslation(translationKey)
                                                        showMenu = false
                                                    }
                                                } else {
                                                    ActionSheetRow(
                                                        icon = Icons.AutoMirrored.Filled.Undo,
                                                        title = stringResource(R.string.show_original),
                                                        subtitle = stringResource(R.string.translation_show_original_subtitle),
                                                    ) {
                                                        broadcastViewModel.showOriginal(translationKey)
                                                        showMenu = false
                                                    }
                                                }
                                            com.kachat.app.services.PostTranslationService.TranslationState.Translating,
                                            is com.kachat.app.services.PostTranslationService.TranslationState.Unavailable -> Unit
                                            null, com.kachat.app.services.PostTranslationService.TranslationState.Failed ->
                                                if (considersTranslation && translationKey in translatableKeys) {
                                                    ActionSheetRow(
                                                        icon = Icons.Default.Translate,
                                                        title = stringResource(R.string.translate),
                                                        subtitle = "Shows this message in ${broadcastViewModel.readerLanguageName()}.",
                                                    ) {
                                                        broadcastViewModel.translateMessage(translationKey, displayContent)
                                                        showMenu = false
                                                    }
                                                }
                                        }
                                        ActionSheetRow(
                                            icon = Icons.Default.Public,
                                            title = stringResource(R.string.view_in_explorer),
                                            subtitle = "Opens this transaction in the block explorer.",
                                        ) {
                                            uriHandler.openUri(kaspaExplorer.txUrl(message.id))
                                            showMenu = false
                                        }
                                        // The pill on the bubble shows WHICH emoji; it has no
                                        // room to say how many or from whom. This does.
                                        if (messageReactions.isNotEmpty()) {
                                            ActionSheetRow(
                                                icon = Icons.Default.Favorite,
                                                title = "Reactions (${messageReactions.size})",
                                                subtitle = "Who reacted, and with what.",
                                            ) {
                                                showMenu = false
                                                showReactions = true
                                            }
                                        }
                                        if (isMine && message.deliveryStatus == "failed") {
                                            ActionSheetRow(
                                                icon = Icons.Default.Refresh,
                                                title = stringResource(R.string.retry_send),
                                                subtitle = "Sends this message again.",
                                            ) {
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
                                            addressDisplayName(addr, contactAliases[addr], senderKnsNames[addr])
                                        },
                                        isMe = { it == myAddress },
                                        // Rooms already fetch every sender's KNS profile for the
                                        // bubbles, so the faces are in hand.
                                        avatarFor = { senderProfiles[it] },
                                        onDismiss = { showReactions = false },
                                    )
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

                                // iOS: a small "edited" chip on the bubble's top inner corner
                                // (10pt secondary on a systemBackground capsule, 8 up), the mark
                                // every platform shows on an edited message.
                                if (editForRow != null) {
                                    Text(
                                        text = "edited",
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .align(if (isMine) Alignment.TopStart else Alignment.TopEnd)
                                            .offset(y = (-8).dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(if (LocalAppColors.current.isDark) Color.Black else Color.White)
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }

                            // The pill is offset ~10dp below the bubble Box and offset reserves no
                            // layout space, so reserve it here - otherwise the pill overlaps the
                            // link preview card / next message below it. iOS pads the bubble 16
                            // below; the column's 3 spacing makes up the rest.
                            if (messageReactions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(13.dp))
                            }

                            // "Translating...", "Translated from Spanish - Show original", or why it
                            // could not be - the same line and words KaPosts uses, under the bubble
                            // rather than inside its Box, so the reaction pill stays on the bubble.
                            // Nothing at all until Translate is picked from the long-press menu.
                            if (translationState != null) {
                                TranslateAffordance(
                                    state = translationState,
                                    canTranslate = false,
                                    showingOriginal = translationKey in translationOriginals,
                                    onTranslate = { broadcastViewModel.translateMessage(translationKey, displayContent) },
                                    onShowOriginal = { broadcastViewModel.showOriginal(translationKey) },
                                    onShowTranslation = { broadcastViewModel.showTranslation(translationKey) },
                                )
                            }

                            // A link mixed with other text keeps the bubble above and stacks the
                            // shared preview card below it, as its own sibling — same placement
                            // (and same reasoning) as 1:1's MessageBubble/group's GroupMessageBubble.
                            // An internal link is always claimed above as the message itself,
                            // so only an external link can still want a card down here.
                            separateLinkPreviewUrl?.takeIf { internalLinkMatch == null }?.let { url ->
                                // Tap-to-load for all broadcast previews - see the entire-link
                                // branch above for why.
                                LinkPreviewCard(url = url, txId = message.id, kaspaExplorer = kaspaExplorer, onDoubleTap = { QuickReactionBarState.activeId = message.id }, isOutgoing = isMine, autoFetch = false)
                            }


                            // The time under every message, exactly as in a 1:1 chat, and under your own
                            // the status after it: "Sending", "Sent" with the green check, "Failed · Tap
                            // to retry" (iOS fc6aec6). Rooms are long feeds, so other days carry the day.
                            MessageTimeLine(
                                timestampMs = message.blockTimestamp,
                                showsDay = true,
                            ) {
                                if (isMine) {
                                    DeliveryStatusLabel(
                                        status = deliveryStatusOf(message.deliveryStatus),
                                        onRetry = if (message.deliveryStatus == "failed") {
                                            { broadcastViewModel.retryBroadcast(message) }
                                        } else null,
                                    )
                                }
                            }

                            // A reaction (not the message) that failed to send: red "Retry" under the
                            // message, paired with the error icon on the reaction pill. Aligned with
                            // ColumnScope.align (NOT a fillMaxWidth Box) so it sits under the pill's
                            // side of the bubble without stretching this Column to the full row width.
                            messageReactions.firstOrNull { it.deliveryStatus == "failed" }?.let { failedReaction ->
                                Text(
                                    text = stringResource(R.string.retry),
                                    color = LocalAppColors.current.danger,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(if (isMine) Alignment.Start else Alignment.End)
                                        .clickable { broadcastViewModel.retryReaction(failedReaction) }
                                )
                            }
                        }
                        if (isMine) {
                            Spacer(Modifier.width(8.dp))
                            avatar()
                        } else {
                            Spacer(Modifier.width(40.dp))
                        }
                        }
                    }
                }
            }
        }

        if (showScrollToBottom && messages.isNotEmpty()) {
            val scrollToLatestLabel = stringResource(R.string.scroll_to_latest)
            // iOS: a 36 disc of regularMaterial edged white at 18% with a soft shadow, holding
            // chevron.down 14 semibold in the label colour - 12 in from the trailing edge and 12
            // above the compose bar.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = padding.calculateBottomPadding() + 12.dp)
                    .size(36.dp)
                    .iosGlass(cornerRadius = 18.dp)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
                    }
                    .semantics { contentDescription = scrollToLatestLabel },
            ) {
                RoomChevronDown()
            }
        }

        }
    }


    if (showFeeEditor) {
        com.kachat.app.ui.theme.IosAlertDialog(
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
                    com.kachat.app.ui.theme.IosTextField(
                        value = feeEditorInput,
                        onValueChange = { feeEditorInput = it },
                        label = { Text(KaspaUnit.label(stringResource(R.string.fee_kas))) },
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
                    // The one exact parser for typed KAS (iOS 16b64bc): "1,5" or "1.5", never a crash.
                    val desiredFeeSompi = KaspaUnit.sompiFromUserText(feeEditorInput)
                    val currentFeeSompi = estimatedFee ?: 0L
                    if (desiredFeeSompi != null && desiredFeeSompi > 0 && currentFeeSompi > 0 && effectiveRate > 0) {
                        val impliedMass = currentFeeSompi / effectiveRate
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


// MARK: - Room screen pieces (iOS PublicChatChannelView)

/** iOS secondarySystemBackground: the room's incoming bubbles, its day dividers and the
 *  composer's reply/edit banners. */
@Composable
private fun roomSecondarySystemBackground(): Color =
    if (LocalAppColors.current.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

/** iOS tertiarySystemBackground: the reply quote above a room message. */
@Composable
private fun roomTertiarySystemBackground(): Color =
    if (LocalAppColors.current.isDark) Color(0xFF2C2C2E) else Color.White

/** A room bubble's fill: OutgoingBubble.color for your own, secondarySystemBackground for
 *  everyone else's (the room's own colour, a shade darker than the 1:1 thread's). */
@Composable
private fun roomBubbleColor(isMine: Boolean): Color =
    if (isMine) LocalAppColors.current.outgoingBubble else roomSecondarySystemBackground()

/** iOS editBanner / replyBanner: a caption glyph in the accent colour, a caption semibold accent
 *  title over a one-line caption preview in secondary, and xmark.circle.fill trailing - padded 12
 *  by 8 on secondarySystemBackground in a 12-rounded box, inset 16 and 8 below the bar's top. */
@Composable
private fun RoomComposerBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    preview: String,
    cancelDescription: String,
    onCancel: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(roomSecondarySystemBackground())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                color = KaspaTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview,
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.Cancel,
            contentDescription = cancelDescription,
            tint = colors.textSecondary,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel),
        )
    }
}

/** The inside of the room's text field: the 1:1 composer's ([ChatComposerFieldDecoration]) with
 *  the room's own placeholder, "Message #name". */
@Composable
private fun RoomComposerFieldDecoration(
    placeholder: String,
    showPlaceholder: Boolean,
    innerTextField: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (showPlaceholder) {
            Text(
                placeholder,
                color = LocalAppColors.current.textTertiary,
                style = chatComposerTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        innerTextField()
    }
}

/** The room composer's mic / send: a 36 x 36 glassBackground(cornerRadius: 12) beside the field. */
@Composable
private fun RoomComposerSquareButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .iosGlass(cornerRadius = 12.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        content()
    }
}

/** SF Symbols' arrow.up.circle.fill at title3: an accent disc (secondary while a send is in
 *  flight) with the arrow cut out of it in the glass behind. */
@Composable
private fun RoomSendGlyph(enabled: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .background(if (enabled) KaspaTeal else LocalAppColors.current.textSecondary, CircleShape),
    ) {
        Icon(
            Icons.Default.ArrowUpward,
            contentDescription = null,
            tint = LocalAppColors.current.surface,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** SF Symbols' chevron.down at 14 semibold in the label colour, drawn as a thin open chevron -
 *  the Material arrow carries padding iOS's glyph does not. */
@Composable
private fun RoomChevronDown() {
    val color = LocalAppColors.current.textPrimary
    androidx.compose.foundation.Canvas(modifier = Modifier.size(width = 14.dp, height = 8.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(inset, inset)
            lineTo(size.width / 2f, size.height - inset)
            lineTo(size.width - inset, inset)
        }
        drawPath(
            path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}
