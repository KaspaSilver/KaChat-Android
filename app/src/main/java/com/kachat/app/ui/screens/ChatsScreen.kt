package com.kachat.app.ui.screens

import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.R
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.KaspaBlue
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.models.avatarFallbackText
import com.kachat.app.models.displayName
import com.kachat.app.models.liveNameFor
import com.kachat.app.models.Conversation
import com.kachat.app.models.GroupMember
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import com.kachat.app.repository.GroupConversation
import com.kachat.app.repository.GroupMessage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Chats tab: one list (iOS a062577). The chats, with every group chat and public room as a circle
 * above them ([ChatCirclesStrip]) under the search bar - swipe sideways for all of them. The
 * three-page Chats / Group Chats / Public Chats pager is gone, and with it the tab badges and the
 * Group Chats page's own long-press sheet.
 *
 * Select mode selects chats and circles together; Select All takes everything visible, and mark
 * read / mark unread / delete act on the whole selection (rooms: removed or switched off, as
 * before). Pull to refresh syncs chats and groups together.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    broadcastViewModel: com.kachat.app.viewmodels.BroadcastViewModel = hiltViewModel(),
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    // The chat list leaves out Message Requests and blocked chats - requests sit behind their own
    // row, and neither counts toward the unread badge (iOS f7ca401, 84e3402).
    val chatListConversations by chatViewModel.chatListConversations.collectAsState()
    // Bot chats are never rows in the chat list (or its search): Kaspa Hub > KaChat Bots is the
    // way in, and once you have written to a bot its chat is a circle above the list (iOS
    // d576941, 5fcf755).
    val conversations = remember(chatListConversations) {
        chatListConversations.filterNot { com.kachat.app.models.KaChatBot.isBot(it.contact.id) }
    }
    val botConversations by chatViewModel.botCircleConversations.collectAsState()
    val messageRequests by chatViewModel.messageRequests.collectAsState()
    val groupConversations by chatViewModel.groupConversations.collectAsState()
    val latestReactionByContact by chatViewModel.latestReactionByContact.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    val isRefreshing by chatViewModel.isRefreshing.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Public rooms picked in Select mode, by channel name (iOS e08c4cc). */
    var selectedRooms by remember { mutableStateOf<Set<String>>(emptySet()) }
    val listedRooms by broadcastViewModel.listedChannels.collectAsState()
    val roomSummaries by broadcastViewModel.roomSummaries.collectAsState()
    var showBulkDeleteConfirmation by remember { mutableStateOf(false) }
    var showPublicChatsSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    // The bottom-right + : one New sheet for the whole list (iOS 5da8ccf), and the QR page one of
    // its options leaves for, shown once the sheet has gone (iOS e6400d6).
    var showNewSheet by remember { mutableStateOf(false) }
    var newSheetQr by remember { mutableStateOf<ChatsNewQr?>(null) }
    // Receive Kaspa's fresh address: decided as the sheet opens (the check is a network round
    // trip) and re-confirmed when the QR page comes up - the same two steps as Profile's.
    var receiveQrAddress by remember { mutableStateOf<String?>(null) }
    var receiveQrResolving by remember { mutableStateOf(true) }
    val chattingBalanceSompi by walletViewModel.balanceSompi.collectAsState()
    /** "Send Kaspa" in the New sheet: Profile's send from the current spending address (iOS f81e8d6). */
    var showSpendingSend by remember { mutableStateOf(false) }
    /** "Request Payment" in the New sheet (iOS c6851d6 `CreatePaymentRequestView`). */
    var showPaymentRequest by remember { mutableStateOf(false) }
    LaunchedEffect(showNewSheet, newSheetQr) {
        if (showNewSheet || newSheetQr == ChatsNewQr.RECEIVE) {
            if (receiveQrAddress == null) receiveQrResolving = true
            // Revealed (on Manage Addresses, watched) only once the Receive QR is shown; the New
            // sheet opening only warms the pick (iOS 24c2094).
            walletViewModel.resolveFreshReceiveAddress(revealing = newSheetQr == ChatsNewQr.RECEIVE) { address ->
                if (address != null) receiveQrAddress = address
                receiveQrResolving = false
            }
        }
    }

    // Group chats and public rooms pinned to the front of the circles row, newest pin first -
    // saved per wallet (iOS a062577).
    val circlePins by remember(myAddress) {
        myAddress?.let { chatViewModel.chatCirclePins(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    /** The circle whose long-press half sheet is up ("g:<groupId>" / "r:<room>", iOS f90a70a). */
    var circleActionTarget by remember { mutableStateOf<String?>(null) }
    /** A group or custom room waiting on its delete confirmation, from that sheet. */
    var circleDeleteGroup by remember { mutableStateOf<String?>(null) }
    var circleDeleteRoom by remember { mutableStateOf<String?>(null) }
    /** A bot chat waiting on its delete confirmation, from its circle's sheet (iOS 5fcf755). */
    var circleDeleteBot by remember { mutableStateOf<com.kachat.app.models.ContactEntity?>(null) }
    /** Pins a circle to the front, or unpins it, with a toast saying which (iOS `toggleCirclePin`). */
    fun toggleCirclePin(id: String) {
        val address = myAddress ?: return
        scope.launch {
            val pinned = chatViewModel.toggleChatCirclePin(address, id)
            IosToasts.show(context.getString(if (pinned) R.string.chats_pinned_to_front else R.string.chats_unpinned))
        }
    }

    // iOS ChatListView's search: the chat's display name (the name you gave it, else its Address
    // Book name, else who it is on chain, else the short address - for your own chat, only who
    // you are on chain), the raw address (so pasting part of an address you recognize still
    // finds it), or any of its messages. The messages are read from the database off the main
    // thread; until that answers the previous answer stays.
    val messageMatchIds by produceState<Set<String>>(initialValue = emptySet(), searchQuery, conversations) {
        value = chatViewModel.contactIdsWithMessageMatching(searchQuery)
    }
    val filteredConversations = remember(conversations, searchQuery, messageMatchIds) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter { convo ->
                convo.contact.displayName.contains(query, ignoreCase = true) ||
                    convo.contact.id.contains(query, ignoreCase = true) ||
                    convo.contact.id in messageMatchIds
            }
        }
    }

    // The circles, as the row shows them: pinned first, then by latest activity, filtered by the
    // search. Select All and the action bar read the same list, so they agree on what's visible.
    val circleItems = remember(groupConversations, listedRooms, roomSummaries, botConversations, searchQuery, circlePins) {
        chatCircleItems(groupConversations, listedRooms, roomSummaries, botConversations, searchQuery, circlePins) { convo ->
            botCircleTitle(context, convo)
        }
    }
    // What Select All and "everything selected" count while searching are iOS's displayedGroups
    // and displayedRooms - not the circles row, which matches a title alone. A group matches by
    // its name, a member's name or address, or a message; a room by its name.
    val searchedGroupIds by produceState<Set<String>?>(initialValue = null, groupConversations, searchQuery) {
        value = chatViewModel.groupIdsMatchingSearch(groupConversations.map { it.group }, searchQuery)
    }
    val visibleGroupIds = searchedGroupIds?.toList()
        ?: circleItems.mapNotNull { (it as? ChatCircle.Group)?.convo?.group?.groupId }
    val visibleRoomNames = searchQuery.trim().let { query ->
        listedRooms.map { it.channelName }.filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
    }
    val selectionCount = selectedContactIds.size + selectedGroupIds.size + selectedRooms.size
    val isEverythingSelected = run {
        val total = filteredConversations.size + visibleGroupIds.size + visibleRoomNames.size
        total > 0 && selectionCount >= total
    }
    fun endSelection() {
        isSelectionMode = false
        selectedContactIds = emptySet()
        selectedGroupIds = emptySet()
        selectedRooms = emptySet()
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        // One list now: the chats and the group circles above them both refresh (iOS a062577).
        onRefresh = { chatViewModel.refreshChats() }
    )

    // Balance only updates reactively while this screen is actively composed —
    // refresh it fresh every time you land on/return to the Chats tab, since a
    // send that happened while on a different screen won't otherwise be reflected
    // until something explicitly asks the network for the current balance again.
    LaunchedEffect(Unit) {
        walletViewModel.refreshBalance()
    }

    // Warms walletViewModel.knsProfile (my own avatar/domain) so it's already populated by the
    // time a chat or group chat thread is opened - those screens read it via the SAME shared
    // walletViewModel instance (passed down from MainShell) but never trigger this refresh
    // themselves, so without this, "my avatar" in a chat's own-message bubble stayed null on
    // every single visit until the user happened to open Manage Addresses/KNS Domains/Edit
    // Profile first.
    LaunchedEffect(Unit) {
        walletViewModel.refreshOwnedDomains()
    }

    // Auto-rename any chat to their KNS domain if they have one, every time the chat
    // list appears — matches iOS's fetchKNSDomainsForAllContacts.
    LaunchedEffect(Unit) {
        chatViewModel.refreshKnsNamesForAllContacts()
        // And their avatars, which nothing else fills in for a chat you have not opened.
        chatViewModel.refreshKnsAvatarsForAllContacts()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* nothing to do either way — notifications just won't show if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }


    if (showPublicChatsSettings) {
        PublicChatsSettingsSheet(
            onDismiss = { showPublicChatsSettings = false },
            broadcastViewModel = broadcastViewModel,
        )
    }

    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = LocalAppColors.current.background,
        // The list runs on under the dock (and the system bar under it); it pads its own end.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(
                modifier = Modifier
                    .background(LocalAppColors.current.background)
                    .statusBarsPadding()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // Header order matches iPhone (ChatListView.swift): status/balance toolbar
                    // row on top, bold large "Chats" title below it, search bar DIRECTLY
                    // underneath the title.
                    TopStatusBar(
                        balance = balance,
                        onStatusClick = { ConnectionStatusOverlayState.open() },
                        dotColorHex = dotColorHex,
                        showAddButton = false,
                        showEditButton = conversations.isNotEmpty() || groupConversations.isNotEmpty() || listedRooms.isNotEmpty(),
                        // Public room settings (which default rooms show), next to Select at all
                        // times now there is no Public Chats page to carry it (iOS a062577).
                        trailingContent = if (!isSelectionMode) {
                            {
                                // iOS: an outlined "gearshape" in the accent colour, before Select.
                                ToolbarIconButton(
                                    Icons.Outlined.Settings,
                                    "Public Chats settings",
                                    { showPublicChatsSettings = true },
                                )
                            }
                        } else null,
                        isEditing = isSelectionMode,
                        onEditClick = {
                            if (isSelectionMode) endSelection() else isSelectionMode = true
                        },
                        // One selection across the chats and the circles above them.
                        selectAllLabel = if (isEverythingSelected) "Deselect All" else "Select All",
                        onSelectAllClick = {
                            if (isEverythingSelected) {
                                selectedContactIds = emptySet()
                                selectedGroupIds = emptySet()
                                selectedRooms = emptySet()
                            } else {
                                selectedContactIds = filteredConversations.map { it.contact.id }.toSet()
                                selectedGroupIds = visibleGroupIds.toSet()
                                selectedRooms = visibleRoomNames.toSet()
                            }
                        }
                    )

                    Text(
                        stringResource(R.string.chats),
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = LargeTitleSize,
                        // measured on the iPhone: the title's cap 44 below the bar's centre
                        modifier = Modifier.padding(top = 9.dp, bottom = 8.dp)
                    )

                    // Search Bar (iOS's .searchable field, 44 high)
                    IosSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = stringResource(R.string.search_chats),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Untargeted system-share landed here (user tapped the plain "KaChat" target
                    // on another app's share sheet, not a specific conversation): prompt them to
                    // pick the chat — whichever thread they open next consumes the pending share
                    // (see ShareIntake / ChatThreadScreen).
                    val pendingShare by com.kachat.app.services.ShareIntake.pending.collectAsState()
                    if (pendingShare != null && pendingShare?.targetContactId == null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(KaspaTeal.copy(alpha = 0.15f))
                                .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.share_pick_chat),
                                color = LocalAppColors.current.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { com.kachat.app.services.ShareIntake.pending.value = null }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                    tint = LocalAppColors.current.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        },
        floatingActionButton = {
            // Same style/placement as Portfolio's add-transaction FAB (see PortfolioScreen.kt) —
            // sits above the app-wide floating tab bar for free, since this screen's own content
            // region is already reserved above it before this Scaffold is even composed.
            // One glass +, opening the New sheet (iOS 5da8ccf). Not while selecting.
            if (isSelectionMode) return@Scaffold
            com.kachat.app.ui.theme.IosGlassFab(
                // Above the dock the list runs under.
                modifier = Modifier.padding(bottom = dockInset),
                onClick = {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    showNewSheet = true
                },
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.chats_new),
            )
        },
        bottomBar = {
            // Mark read, mark unread and delete, for everything selected: chats, group circles and
            // room circles alike (iOS a062577 `selectionActionBar`).
            if (isSelectionMode) {
                Column(modifier = Modifier.background(LocalAppColors.current.background).navigationBarsPadding()) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (selectedGroupIds.isNotEmpty()) chatViewModel.markGroupsAsRead(selectedGroupIds)
                                selectedRooms.forEach { broadcastViewModel.markRoomRead(it) }
                                if (selectedContactIds.isNotEmpty()) chatViewModel.markContactsAsRead(selectedContactIds)
                                endSelection()
                            },
                            enabled = selectionCount > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailRead, stringResource(R.string.read), tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                        Button(
                            onClick = {
                                if (selectedContactIds.isNotEmpty()) chatViewModel.markContactsAsUnread(selectedContactIds)
                                if (selectedGroupIds.isNotEmpty()) chatViewModel.markGroupsAsUnread(selectedGroupIds)
                                selectedRooms.forEach { broadcastViewModel.markRoomUnread(it) }
                                endSelection()
                            },
                            enabled = selectionCount > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailUnread, stringResource(R.string.unread), tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                        Button(
                            onClick = { showBulkDeleteConfirmation = true },
                            enabled = selectionCount > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = LocalAppColors.current.danger, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pullRefresh(pullRefreshState)
        ) {
            // Deleting never happens straight from the row - it stages a confirmation below,
            // since a delete permanently wipes local message history.
            var contactToDelete by remember { mutableStateOf<String?>(null) }
            // Long-press target - which conversation's action sheet is open.
            var menuContactId by remember { mutableStateOf<String?>(null) }
            // The row's "Add to Address Book" tile: the chat whose entry sheet is up (iOS 98f3728).
            var addressBookTarget by remember { mutableStateOf<com.kachat.app.models.ContactEntity?>(null) }
            val addressBookEntries = rememberAddressBookEntries()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // The last row scrolls clear of the dock it runs under (none while selecting:
                // the selection bar replaces the dock and pads itself).
                contentPadding = PaddingValues(bottom = if (isSelectionMode) 0.dp else dockInset),
            ) {
                // Group chats and public rooms, as circles under the search bar - swipe sideways
                // for all of them. Tap opens, hold opens its half sheet, Select mode selects them
                // too.
                item(key = "chat_circles") {
                    ChatCirclesStrip(
                        items = circleItems,
                        pins = circlePins,
                        isSelectionMode = isSelectionMode,
                        isSelected = { item ->
                            when (item) {
                                is ChatCircle.Group -> item.convo.group.groupId in selectedGroupIds
                                is ChatCircle.Room -> item.name in selectedRooms
                                // a bot chat is deleted from its own half sheet, not in Select mode
                                is ChatCircle.Bot -> false
                            }
                        },
                        onTap = { item ->
                            when (item) {
                                is ChatCircle.Group -> {
                                    val id = item.convo.group.groupId
                                    if (isSelectionMode) {
                                        selectedGroupIds = if (id in selectedGroupIds) selectedGroupIds - id else selectedGroupIds + id
                                    } else {
                                        navController.navigate("group_chat/$id")
                                    }
                                }
                                is ChatCircle.Room -> {
                                    val name = item.name
                                    if (isSelectionMode) {
                                        selectedRooms = if (name in selectedRooms) selectedRooms - name else selectedRooms + name
                                    } else {
                                        navController.navigate("broadcast_channel/$name")
                                    }
                                }
                                is ChatCircle.Bot -> {
                                    if (!isSelectionMode) navController.navigate("chat/${item.convo.contact.id}")
                                }
                            }
                        },
                        // Hold: the circle's half sheet, like a chat row's (iOS f90a70a).
                        onLongPress = { item -> circleActionTarget = item.id },
                    )
                }
                // People who wrote first and haven't been accepted - one row, always there,
                // right above your own chat; hidden only while searching or selecting
                // (NO_HANDSHAKE_MESSAGING.md, iOS f7ca401 / 74bd52c).
                if (searchQuery.isBlank() && !isSelectionMode) {
                    item(key = "message_requests_row") {
                        Column {
                            MessageRequestsRow(count = messageRequests.size) {
                                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                                navController.navigate("message_requests")
                            }
                            // iOS's List draws its separator under this row too.
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                                color = LocalAppColors.current.textTertiary.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                if (filteredConversations.isNotEmpty()) {
                    // 4.0: the Broadcasts entry card is gone - Broadcasts is a dock tab now,
                    // riding the Chats-slot cycle when the dock is full (matches iOS).
                    items(filteredConversations, key = { it.contact.id }) { convo ->
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().background(LocalAppColors.current.background)
                            ) {
                                if (isSelectionMode) {
                                    Icon(
                                        imageVector = if (convo.contact.id in selectedContactIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = stringResource(R.string.select_chat),
                                        tint = if (convo.contact.id in selectedContactIds) KaspaTeal else LocalAppColors.current.textSecondary,
                                        modifier = Modifier.padding(start = 16.dp).size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    ConversationRow(
                                        convo,
                                        latestReactionByContact[convo.contact.id],
                                        myAddress,
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                haptic(com.kachat.app.util.IosHaptic.IMPACT_MEDIUM)
                                                menuContactId = convo.contact.id
                                            }
                                        }
                                    ) {
                                        if (isSelectionMode) {
                                            selectedContactIds = if (convo.contact.id in selectedContactIds) {
                                                selectedContactIds - convo.contact.id
                                            } else {
                                                selectedContactIds + convo.contact.id
                                            }
                                        } else {
                                            navController.navigate("chat/${convo.contact.id}")
                                        }
                                    }
                                    // iOS 26: from the text's start (16 + 50 + 12) to 16 short of the edge.
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                                        color = LocalAppColors.current.textTertiary.copy(alpha = 0.5f)
                                    )
                                }
                            }
                            if (menuContactId == convo.contact.id) {
                                // A sheet, not a dropdown: each option carries a line saying
                                // what it does (the TalkBack hint, now the options are square
                                // tiles - iOS cdac6d0), and Silence needs one - it is not
                                // obvious that it overrides the app-wide notification setting.
                                val isSilent = com.kachat.app.models.ContactNotificationMode
                                    .fromName(convo.contact.notificationOverride) ==
                                    com.kachat.app.models.ContactNotificationMode.OFF
                                // Two rows of tiles tall, as iOS sizes it (sheetHeight(tiles: 4)).
                                TileActionSheet(
                                    title = convo.contact.displayName,
                                    onDismiss = { menuContactId = null },
                                    height = ActionSheetTileMetrics.sheetHeight(tiles = 4),
                                ) {
                                        if (convo.unreadCount > 0) {
                                            ActionSheetRow(
                                                icon = Icons.Outlined.MarkEmailRead,
                                                title = stringResource(R.string.mark_as_read),
                                                subtitle = "Clears the unread badge on this chat.",
                                            ) {
                                                menuContactId = null
                                                chatViewModel.markAsRead(convo.contact.id)
                                            }
                                        } else {
                                            ActionSheetRow(
                                                icon = Icons.Outlined.MarkEmailUnread,
                                                title = stringResource(R.string.mark_as_unread),
                                                subtitle = "Puts the unread badge back so you come across it again.",
                                            ) {
                                                menuContactId = null
                                                chatViewModel.markAsUnread(convo.contact.id)
                                            }
                                        }
                                        ActionSheetRow(
                                            icon = if (isSilent) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                                            title = if (isSilent) "Unsilence" else "Silence",
                                            subtitle = if (isSilent) {
                                                "Notifications from this chat resume."
                                            } else {
                                                "No notification from this chat, whatever your app-wide setting says."
                                            },
                                        ) {
                                            menuContactId = null
                                            chatViewModel.updateContactNotificationOverride(
                                                convo.contact.id,
                                                if (isSilent) null else com.kachat.app.models.ContactNotificationMode.OFF
                                            )
                                        }
                                        // Save this person's address (or edit their saved entry)
                                        // without leaving the list. Not on your own chat.
                                        if (!convo.contact.id.equals(myAddress, ignoreCase = true)) {
                                            val saved = addressBookEntries.isNotEmpty() &&
                                                com.kachat.app.services.AddressBookManager.shared?.entry(convo.contact.id) != null
                                            ActionSheetRow(
                                                icon = if (saved) Icons.Filled.Book else Icons.Outlined.Book,
                                                title = stringResource(if (saved) R.string.ab_address_book else R.string.ab_add_to_address_book),
                                                subtitle = stringResource(if (saved) R.string.ab_row_edit_subtitle else R.string.ab_row_add_subtitle),
                                            ) {
                                                menuContactId = null
                                                addressBookTarget = convo.contact
                                            }
                                        }
                                        // Your chat with yourself cannot be deleted - it is always
                                        // there, first in the list (iOS ef4f183).
                                        if (!convo.contact.id.equals(myAddress, ignoreCase = true)) ActionSheetRow(
                                            icon = Icons.Outlined.Delete,
                                            title = stringResource(R.string.delete),
                                            subtitle = "Removes this chat and its messages from this device.",
                                            tint = LocalAppColors.current.danger,
                                        ) {
                                            menuContactId = null
                                            contactToDelete = convo.contact.id
                                        }
                                }
                            }
                        }
                    }
                    item {
                        // The chats listed - while searching, the ones that match (iOS totalCount).
                        val chatCount = filteredConversations.size
                        Text(
                            text = "$chatCount ${if (chatCount == 1) "chat" else "chats"}",
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    }
                }
            }

            // Below the circles row rather than over it, and only when there are no chats at all:
            // a search that matches nothing leaves the list empty, as iOS does (ChatListView
            // emptyStateView, centred in the list and 120 lower).
            if (filteredConversations.isEmpty() && searchQuery.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 120.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Forum,
                        contentDescription = null,
                        tint = LocalAppColors.current.textSecondary,
                        modifier = Modifier.size(60.dp),
                    )
                    Text(
                        stringResource(R.string.no_conversations_yet),
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.start_a_new_chat_by_adding),
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            addressBookTarget?.let { contact ->
                AddressBookEntryEditor(
                    address = contact.id,
                    suggestedName = contact.alias?.takeIf { it.isNotBlank() } ?: contact.displayName,
                    onDone = {},
                    onDismiss = { addressBookTarget = null },
                )
            }

            contactToDelete?.let { contactId ->
                val label = filteredConversations.find { it.contact.id == contactId }
                    ?.contact?.displayName ?: "this chat"
                com.kachat.app.ui.theme.IosAlertDialog(
                    onDismissRequest = { contactToDelete = null },
                    containerColor = LocalAppColors.current.surface,
                    title = { Text("Delete Chat with $label", color = LocalAppColors.current.textPrimary) },
                    text = {
                        Text(
                            stringResource(R.string.this_permanently_deletes_every_message_with),
                            color = LocalAppColors.current.textSecondary
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            chatViewModel.deleteChat(contactId)
                            contactToDelete = null
                        }) {
                            Text(stringResource(R.string.delete), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { contactToDelete = null }) {
                            Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                        }
                    }
                )
            }

            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = LocalAppColors.current.surface,
                contentColor = KaspaTeal
            )
        }

        // One confirmation for the whole selection - chats, groups and rooms together. Its title
        // names the one kind when only one is picked (iOS a062577 `bulkDeleteAlertTitle`), and its
        // message carries a paragraph per kind picked.
        if (showBulkDeleteConfirmation) {
            val chats = selectedContactIds.size
            val groups = selectedGroupIds.size
            val rooms = selectedRooms.size
            val roomsBody = stringResource(R.string.public_chats_bulk_delete_body)
            com.kachat.app.ui.theme.IosAlertDialog(
                onDismissRequest = { showBulkDeleteConfirmation = false },
                containerColor = LocalAppColors.current.surface,
                title = {
                    Text(
                        when {
                            groups == 0 && rooms == 0 -> "Delete $chats Chat${if (chats == 1) "" else "s"}?"
                            chats == 0 && rooms == 0 -> "Delete $groups Group${if (groups == 1) "" else "s"}?"
                            chats == 0 && groups == 0 -> "Delete $rooms Public Chat${if (rooms == 1) "" else "s"}?"
                            else -> "Delete ${chats + groups + rooms} Selected?"
                        },
                        color = LocalAppColors.current.textPrimary
                    )
                },
                text = {
                    Text(
                        listOfNotNull(
                            "This permanently deletes every message in each selected chat from this device. This cannot be undone."
                                .takeIf { chats > 0 },
                            "This removes each selected group and its messages from this device. This cannot be undone, and other members won't be notified."
                                .takeIf { groups > 0 },
                            roomsBody.takeIf { rooms > 0 },
                        ).joinToString("\n\n"),
                        color = LocalAppColors.current.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (selectedContactIds.isNotEmpty()) chatViewModel.deleteChats(selectedContactIds)
                        if (selectedGroupIds.isNotEmpty()) chatViewModel.deleteGroupChats(selectedGroupIds)
                        selectedRooms.forEach { broadcastViewModel.removeFromList(it) }
                        showBulkDeleteConfirmation = false
                        endSelection()
                    }) {
                        Text(stringResource(R.string.delete), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBulkDeleteConfirmation = false }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            )
        }
    }

    circleActionTarget?.let { id ->
        ChatCircleActionSheet(
            id = id,
            isPinned = id in circlePins,
            groupConversations = groupConversations,
            listedRooms = listedRooms,
            roomSummaries = roomSummaries,
            botConversations = botConversations,
            chatViewModel = chatViewModel,
            broadcastViewModel = broadcastViewModel,
            onDismiss = { circleActionTarget = null },
            onTogglePin = { toggleCirclePin(id) },
            onDeleteGroup = { circleDeleteGroup = it },
            onDeleteRoom = { circleDeleteRoom = it },
            onDeleteBot = { circleDeleteBot = it },
        )
    }
    // The chat row's delete confirmation, for a bot chat's circle (iOS 5fcf755 rowDeleteContact):
    // iOS's "Delete Chat?" alert, its title and text as they are there.
    circleDeleteBot?.let { contact ->
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { circleDeleteBot = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.delete_chat_q), color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.delete_chat_alert_message),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.deleteChat(contact.id)
                    circleDeleteBot = null
                }) {
                    Text(stringResource(R.string.delete), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { circleDeleteBot = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
    circleDeleteGroup?.let { groupId ->
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { circleDeleteGroup = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete Group?", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(stringResource(R.string.this_removes_the_group_and_its), color = LocalAppColors.current.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.deleteGroupChat(groupId)
                    circleDeleteGroup = null
                }) {
                    Text(stringResource(R.string.delete), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { circleDeleteGroup = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
    circleDeleteRoom?.let { name ->
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { circleDeleteRoom = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.delete_room_title), color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "Every message cached for this room on this device is deleted. This cannot be undone - rejoining later starts with no history.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    broadcastViewModel.leaveChannel(name)
                    circleDeleteRoom = null
                }) {
                    Text(stringResource(R.string.delete), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { circleDeleteRoom = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    // The New sheet (iOS 5da8ccf -> e6400d6): New Chat / New Group Chat swap it to the create
    // screen, New Public Chat pushes a room name inside it, and the two QR options close it and
    // then bring up Profile's white QR page.
    if (showNewSheet) {
        ChatsNewSheet(
            chatViewModel = chatViewModel,
            broadcastViewModel = broadcastViewModel,
            onDismiss = { showNewSheet = false },
            onChatCreated = { address -> navController.navigate("chat/$address") },
            onGroupCreated = { groupId -> navController.navigate("group_chat/$groupId") },
            onRoomJoined = { name ->
                // Opened like a tapped circle when the name is safe in a route; otherwise the
                // joined room waits in the circles row.
                if (KaChatLink.sanitizeChannelName(name) == name) navController.navigate("broadcast_channel/$name")
            },
            onShowQr = { newSheetQr = it },
            onSendKaspa = { showSpendingSend = true },
            onRequestPayment = { showPaymentRequest = true },
        )
    }
    // A sheet over Chats once the New sheet has gone, as iOS presents it (c6851d6): the link to
    // copy or share, paid to a brand-new address or one you choose (iOS 6dee531).
    if (showPaymentRequest) {
        CreatePaymentRequestSheet(
            walletViewModel = walletViewModel,
            onDismissed = { showPaymentRequest = false },
        )
    }
    // A sheet over Chats, as iOS presents it once the New sheet has gone (f81e8d6).
    if (showSpendingSend) {
        ChatsSpendingSendLauncher(walletViewModel = walletViewModel, onDone = { showSpendingSend = false })
    }
    // Each in a sheet of its own, Done at the top right, as iOS presents them once the New sheet
    // has gone (e6400d6): the white QR page with the address's balance in its bar.
    when (newSheetQr) {
        ChatsNewQr.FUND_CHATTING -> QrPageSheet(onDismiss = { newSheetQr = null }, white = true) {
            ChattingAddressQrPage(
                address = myAddress ?: "",
                balanceSompi = chattingBalanceSompi,
                dismiss = QrPageDismiss.DONE,
                onDismiss = { newSheetQr = null },
            )
        }
        ChatsNewQr.RECEIVE -> QrPageSheet(onDismiss = { newSheetQr = null }, white = receiveQrAddress != null) {
            ReceiveKaspaQrPage(
                address = receiveQrAddress,
                resolving = receiveQrResolving,
                dismiss = QrPageDismiss.DONE,
                onDismiss = { newSheetQr = null },
                walletViewModel = walletViewModel,
                title = stringResource(R.string.receive_kaspa),
            )
        }
        null -> Unit
    }
}

/** One circle in [ChatCirclesStrip]: a group chat, a public room or a bot chat. [id] is the pin
 *  id - "g:<groupId>" / "r:<room>" / "b:<bot address, lowercased>", as iOS's (a062577, 5fcf755). */
private sealed interface ChatCircle {
    val id: String
    val title: String
    val unread: Int

    data class Group(val convo: GroupConversation) : ChatCircle {
        override val id get() = "g:${convo.group.groupId}"
        override val title get() = convo.group.name
        override val unread get() = convo.unreadCount
    }

    data class Room(val name: String, override val unread: Int) : ChatCircle {
        override val id get() = "r:$name"
        override val title get() = "#$name"
    }

    /** A bot chat you have written to ([ChatViewModel.botCircleConversations]); [title] is the
     *  bot's name, else the contact's. */
    data class Bot(val convo: com.kachat.app.models.Conversation, override val title: String) : ChatCircle {
        override val id get() = "b:${convo.contact.id.lowercase()}"
        override val unread get() = convo.unreadCount
    }
}

/** A bot chat circle's name: the bot's own, else what the contact is called (iOS 5fcf755). */
private fun botCircleTitle(context: android.content.Context, convo: com.kachat.app.models.Conversation): String =
    com.kachat.app.models.KaChatBot.bot(convo.contact.id)?.let { context.getString(it.nameRes) }
        ?: convo.contact.alias?.takeIf { it.isNotBlank() }
        ?: convo.contact.displayName

/**
 * The circles in row order (iOS `ChatCirclesStrip.items`): every group, every listed room and
 * every bot chat you have written to, filtered by the search on its title; the pinned ones first,
 * in pin order, then the rest by latest activity - a group's last message, else when it was
 * created; a room's last message, else when it was joined; a bot chat's last message.
 */
private fun chatCircleItems(
    groups: List<GroupConversation>,
    rooms: List<com.kachat.app.models.BroadcastChannelEntity>,
    summaries: Map<String, com.kachat.app.repository.BroadcastRepository.RoomSummary>,
    bots: List<com.kachat.app.models.Conversation>,
    searchQuery: String,
    pins: List<String>,
    botTitle: (com.kachat.app.models.Conversation) -> String,
): List<ChatCircle> {
    var dated: List<Pair<ChatCircle, Long>> =
        groups.map { ChatCircle.Group(it) to (it.lastMessage?.blockTimestamp ?: it.group.createdAt) } +
            rooms.map { room ->
                val summary = summaries[room.channelName]
                ChatCircle.Room(room.channelName, summary?.unreadCount ?: 0) to
                    (summary?.lastMessage?.blockTimestamp ?: room.joinedAt)
            } +
            bots.map { ChatCircle.Bot(it, botTitle(it)) to (it.lastMessage?.blockTimestamp ?: 0L) }
    val query = searchQuery.trim()
    if (query.isNotEmpty()) dated = dated.filter { it.first.title.contains(query, ignoreCase = true) }
    val byId = dated.associate { it.first.id to it.first }
    val pinned = pins.mapNotNull { byId[it] }
    val pinnedIds = pinned.map { it.id }.toSet()
    return pinned + dated.filter { it.first.id !in pinnedIds }.sortedByDescending { it.second }.map { it.first }
}

/**
 * Group chats and public rooms as a row of circles above the chats list, under the search bar
 * (iOS a062577 `ChatCirclesStrip`). Swipe sideways for all of them. A red count shows messages
 * from others since you last opened it; a pin marks the ones pinned to the front. Tap opens; hold
 * opens its half sheet (read state, pin, notifications, delete - iOS f90a70a); in Select mode a
 * tap selects instead (a check on the circle), for the list's mark read / unread / delete bar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatCirclesStrip(
    items: List<ChatCircle>,
    pins: List<String>,
    isSelectionMode: Boolean,
    isSelected: (ChatCircle) -> Boolean,
    onTap: (ChatCircle) -> Unit,
    onLongPress: (ChatCircle) -> Unit,
) {
    if (items.isEmpty()) {
        Spacer(Modifier.fillMaxWidth().height(1.dp))
        return
    }
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val holdHint = stringResource(R.string.chats_circle_hold_for_options)
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(items, key = { it.id }) { item ->
            val selected = isSelected(item)
            val pinned = item.id in pins
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .width(68.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTap(item) },
                        onLongClick = if (isSelectionMode) null else ({
                            haptic(com.kachat.app.util.IosHaptic.IMPACT_MEDIUM)
                            onLongPress(item)
                        }),
                    )
                    // Its name, then the hint (iOS: label + "Hold for options").
                    .semantics { contentDescription = "${item.title}. $holdHint" },
            ) {
                Box(modifier = Modifier.size(60.dp)) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .alpha(if (isSelectionMode && !selected) 0.55f else 1f)
                            .clip(CircleShape)
                            .then(
                                if (isSelectionMode && selected) Modifier.border(3.dp, KaspaTeal, CircleShape)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChatCircleAvatar(item)
                    }
                    if (isSelectionMode) {
                        Icon(
                            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) KaspaTeal else colors.textSecondary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(20.dp)
                                .background(colors.background, CircleShape),
                        )
                    } else if (item.unread > 0) {
                        ChatsUnreadBadge(
                            count = item.unread,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp),
                        )
                    }
                    if (pinned && !isSelectionMode) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset(x = (-2).dp, y = 2.dp)
                                .background(KaspaTeal, CircleShape)
                                .padding(4.dp),
                        ) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(10.dp),
                            )
                        }
                    }
                }
                // The whole name: a long one carries on underneath (up to three lines) rather than
                // being cut off, centered. Every name reserves the three lines, so every circle is
                // the same height and the row has one known height - nothing for the chat list
                // below to cut off, and the circles stay level (iOS 7a8ef1f, 55cb8e6).
                Text(
                    item.title,
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(68.dp),
                )
            }
        }
    }
}

/** A circle's face: the group's photo, else the groups glyph; a room's "#". On the accent tint,
 *  as iOS's (a062577). */
@Composable
private fun ChatCircleAvatar(item: ChatCircle) {
    when (item) {
        is ChatCircle.Group -> {
            val photoHex = item.convo.group.photoHex
            if (!photoHex.isNullOrEmpty()) {
                GroupAvatar(photoHex = photoHex, size = 60.dp)
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Groups, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(28.dp))
                }
            }
        }
        is ChatCircle.Room -> Box(
            modifier = Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("#", color = KaspaTeal, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = IosRoundedFontFamily)
        }
        // The bot's own icon, as on its Kaspa Hub card (iOS 5fcf755).
        is ChatCircle.Bot -> Box(
            modifier = Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                com.kachat.app.models.KaChatBot.bot(item.convo.contact.id)?.icon ?: com.kachat.app.models.KaChatBot.fallbackIcon,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * A group or room circle's long-press half sheet - the chat row's options for that kind (iOS
 * f90a70a `circleActionSheet`): read state, pin to the front, notifications, (rooms) the room
 * link, and delete. A group's delete and a custom room's are confirmed first ([onDeleteGroup] /
 * [onDeleteRoom]); a default room is only switched off, as in Public Chats settings. As square
 * tiles, like every long-press menu (iOS cdac6d0).
 */
@Composable
private fun ChatCircleActionSheet(
    id: String,
    isPinned: Boolean,
    groupConversations: List<GroupConversation>,
    listedRooms: List<com.kachat.app.models.BroadcastChannelEntity>,
    roomSummaries: Map<String, com.kachat.app.repository.BroadcastRepository.RoomSummary>,
    botConversations: List<com.kachat.app.models.Conversation>,
    chatViewModel: ChatViewModel,
    broadcastViewModel: com.kachat.app.viewmodels.BroadcastViewModel,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleteGroup: (String) -> Unit,
    onDeleteRoom: (String) -> Unit,
    onDeleteBot: (com.kachat.app.models.ContactEntity) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val silentGroups by chatViewModel.groupSilent.collectAsState()
    fun toast(text: String) = IosToasts.show(text)

    @Composable
    fun PinRow() {
        ActionSheetRow(
            icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
            title = stringResource(if (isPinned) R.string.chats_circle_unpin else R.string.chats_circle_pin_to_front),
            subtitle = stringResource(if (isPinned) R.string.chats_circle_unpin_hint else R.string.chats_circle_pin_hint),
        ) {
            onDismiss()
            onTogglePin()
        }
    }

    if (id.startsWith("g:")) {
        val convo = groupConversations.firstOrNull { "g:${it.group.groupId}" == id } ?: return
        val groupId = convo.group.groupId
        val isSilent = groupId in silentGroups
        // Fixed at two rows of tiles, as iOS sizes a circle's sheet (sheetHeight(tiles: 6)).
        TileActionSheet(title = convo.group.name, onDismiss = onDismiss, height = ActionSheetTileMetrics.sheetHeight(tiles = 6)) {
                if (convo.unreadCount > 0) {
                    ActionSheetRow(
                        icon = Icons.Outlined.MarkEmailRead,
                        title = stringResource(R.string.mark_as_read),
                        subtitle = "Clears the unread badge on this group.",
                    ) {
                        onDismiss()
                        chatViewModel.markGroupsAsRead(listOf(groupId))
                    }
                } else {
                    ActionSheetRow(
                        icon = Icons.Outlined.MarkEmailUnread,
                        title = stringResource(R.string.mark_as_unread),
                        subtitle = "Puts the unread badge back so you come across it again.",
                    ) {
                        onDismiss()
                        chatViewModel.markGroupsAsUnread(listOf(groupId))
                    }
                }
                PinRow()
                ActionSheetRow(
                    icon = if (isSilent) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                    title = if (isSilent) "Unsilence" else "Silence",
                    subtitle = if (isSilent) {
                        "Notifications from this group resume, including mentions."
                    } else {
                        "No notification from this group, mentions included."
                    },
                ) {
                    onDismiss()
                    chatViewModel.setGroupSilent(groupId, !isSilent)
                }
                ActionSheetRow(
                    icon = Icons.Outlined.Delete,
                    title = stringResource(R.string.delete),
                    subtitle = "Removes this group and its messages from this device.",
                    tint = LocalAppColors.current.danger,
                ) {
                    onDismiss()
                    onDeleteGroup(groupId)
                }
        }
    } else if (id.startsWith("b:")) {
        // A bot chat's circle (iOS 5fcf755, localized in 4a43e33): read state, pin to the front,
        // Silence, and Delete - confirmed, and the circle stays gone until you write to the bot
        // again.
        val convo = botConversations.firstOrNull { "b:${it.contact.id.lowercase()}" == id } ?: return
        val contact = convo.contact
        val isSilent = com.kachat.app.models.ContactNotificationMode.fromName(contact.notificationOverride) ==
            com.kachat.app.models.ContactNotificationMode.OFF
        val title = com.kachat.app.models.KaChatBot.bot(contact.id)?.let { stringResource(it.nameRes) } ?: contact.displayName
        TileActionSheet(title = title, onDismiss = onDismiss, height = ActionSheetTileMetrics.sheetHeight(tiles = 6)) {
                if (convo.unreadCount > 0) {
                    ActionSheetRow(
                        icon = Icons.Outlined.MarkEmailRead,
                        title = stringResource(R.string.mark_as_read),
                        subtitle = stringResource(R.string.bot_sheet_clears_unread),
                    ) {
                        onDismiss()
                        chatViewModel.markAsRead(contact.id)
                    }
                } else {
                    ActionSheetRow(
                        icon = Icons.Outlined.MarkEmailUnread,
                        title = stringResource(R.string.mark_as_unread),
                        subtitle = "Puts the unread badge back so you come across it again.",
                    ) {
                        onDismiss()
                        chatViewModel.markAsUnread(contact.id)
                    }
                }
                PinRow()
                ActionSheetRow(
                    icon = if (isSilent) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                    title = if (isSilent) "Unsilence" else "Silence",
                    subtitle = stringResource(if (isSilent) R.string.bot_sheet_notifications_resume else R.string.bot_sheet_no_notification),
                ) {
                    onDismiss()
                    chatViewModel.updateContactNotificationOverride(
                        contact.id,
                        if (isSilent) null else com.kachat.app.models.ContactNotificationMode.OFF
                    )
                }
                ActionSheetRow(
                    icon = Icons.Outlined.Delete,
                    title = stringResource(R.string.delete),
                    subtitle = stringResource(R.string.bot_sheet_delete_subtitle),
                    tint = LocalAppColors.current.danger,
                ) {
                    onDismiss()
                    onDeleteBot(contact)
                }
        }
    } else if (id.startsWith("r:")) {
        val name = id.removePrefix("r:")
        val channel = listedRooms.firstOrNull { it.channelName == name }
        val isCurated = name in com.kachat.app.models.FeaturedBroadcastChannels.CURATED_NAMES
        // the indexer keeps it, so push covers it with the app closed (iOS 21c480d)
        val trackedRoomsVersion by com.kachat.app.services.IndexerTrackedRooms.changes.collectAsState()
        val isTracked = remember(name, trackedRoomsVersion) { com.kachat.app.models.FeaturedBroadcastChannels.isIndexerTracked(name) }
        val notifyOn = channel?.notifyEnabled == true
        TileActionSheet(title = "#$name", onDismiss = onDismiss, height = ActionSheetTileMetrics.sheetHeight(tiles = 6)) {
                if ((roomSummaries[name]?.unreadCount ?: 0) > 0) {
                    ActionSheetRow(
                        icon = Icons.Outlined.MarkEmailRead,
                        title = stringResource(R.string.mark_as_read),
                        subtitle = "Clears the unread badge on this room.",
                    ) {
                        onDismiss()
                        broadcastViewModel.markRoomRead(name)
                    }
                } else {
                    ActionSheetRow(
                        icon = Icons.Outlined.MarkEmailUnread,
                        title = stringResource(R.string.mark_as_unread),
                        subtitle = "Puts the unread badge back so you come across it again.",
                    ) {
                        onDismiss()
                        broadcastViewModel.markRoomUnread(name)
                    }
                }
                PinRow()
                ActionSheetRow(
                    icon = if (notifyOn) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    title = if (notifyOn) "Turn Off Notifications" else "Turn On Notifications",
                    subtitle = when {
                        notifyOn -> "No notification for new messages in this room."
                        isTracked -> "Notifies you of new messages, even when the app is closed."
                        else -> "Notifies you of new messages while the app is open."
                    },
                ) {
                    onDismiss()
                    broadcastViewModel.setNotifyEnabledEnsuringJoined(name, !notifyOn)
                    toast(
                        when {
                            notifyOn -> "Notifications are off for this public chat"
                            isTracked -> "You'll get notifications for new messages in this public chat, even when the app is closed"
                            else -> "You'll get a notification for new messages in this public chat as long as your app remains open"
                        }
                    )
                }
                ActionSheetRow(
                    icon = Icons.Default.Link,
                    title = "Copy Room Link",
                    subtitle = "A kachat.app link that opens this room.",
                ) {
                    onDismiss()
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(KaChatLink.broadcastWebUrl(name)))
                    toast("Room link copied")
                }
                if (isCurated) {
                    ActionSheetRow(
                        icon = Icons.Outlined.Delete,
                        title = stringResource(R.string.delete),
                        subtitle = stringResource(R.string.default_room_delete_subtitle),
                        tint = LocalAppColors.current.danger,
                    ) {
                        onDismiss()
                        broadcastViewModel.removeFromList(name)
                    }
                } else if (channel != null) {
                    ActionSheetRow(
                        icon = Icons.Outlined.Delete,
                        title = stringResource(R.string.delete),
                        subtitle = "Removes this room and its messages from this device.",
                        tint = LocalAppColors.current.danger,
                    ) {
                        onDismiss()
                        onDeleteRoom(name)
                    }
                }
        }
    }
}

/** The red unread count on the group and room circles (iOS `ChatsTabUnreadBadge`) - hidden at 0. */
@Composable
private fun ChatsUnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(color = LocalAppColors.current.danger, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * A one-line preview of a message body, for the chat list and anywhere else a raw body would
 * otherwise leak the audio-message or reply JSON blob to the user. [contactLabel] names the other
 * party, used when they're the one who sent a reply ("Alice replied to ..." vs "You replied to ...").
 */
private fun messagePreviewText(message: MessageEntity?, contactLabel: String, context: android.content.Context): String? {
    val body = message?.plaintextBody ?: return null
    // Hidden placeholders (cross-device, undecryptable) never surface anywhere. The chat list's
    // latest-message query already skips them; this is a backstop for any other route.
    if (MessageEntity.isHiddenPlaceholder(body)) return ""
    val replyContent = MessageReply.parseOrNull(body)
    if (replyContent != null) {
        val who = if (message.direction == "sent") "You" else contactLabel
        return "$who replied to \"${replyContent.replyToPreview}\""
    }
    // One head scan instead of four full parses. Each of those deserializes the WHOLE payload,
    // and for an inline photo or voice message that is tens of kilobytes of base64 - four times
    // over, per visible row, on every recomposition. See InlineMediaSniff.
    com.kachat.app.util.InlineMediaSniff.mimeType(body)?.let { mime ->
        return when {
            mime.startsWith("audio/") -> "🎤 Audio message"
            mime.startsWith("image/") -> "📷 Photo"
            mime.startsWith("video/") -> "🎬 Video"
            else -> "📎 File"
        }
    }
    // A message that is nothing but a link back into KaChat previews as what it OPENS rather than
    // as a raw link - matching the card the bubble itself draws for it (iOS formatPreview).
    when (val link = KaChatLink.parse(body)) {
        is KaChatLinkRef.KaPost -> return "Shared a KaPosts post"
        is KaChatLinkRef.BroadcastRoom -> return "Public chat room #${link.channel}"
        is KaChatLinkRef.Profile -> return "Shared a KaChat profile"
        is KaChatLinkRef.PaymentRequest -> return com.kachat.app.util.KaspaUnit.label(
            context.getString(R.string.pr_preview, link.request.amountText)
        )
        null -> {}
    }
    if (com.kachat.app.util.ChessMessage.parseOrNull(body) != null) return "♟️ Chess game"
    com.kachat.app.util.CallCodec.parseOrNull(body)?.let { return com.kachat.app.util.CallCodec.listPreview(it) }
    // Never a link in the row - see NextcloudShareSniff.linkSafePreview.
    return com.kachat.app.util.NextcloudShareSniff.linkSafePreview(body)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    convo: Conversation,
    latestReaction: com.kachat.app.services.database.LatestReactionRow?,
    myAddress: String?,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // iOS ConversationRow: a 50pt avatar 12pt from the text, a list row's insets plus 4.
            // iOS: the row's own 4 plus the List's row insets around the 50 avatar - about 89 a row.
            .padding(horizontal = 16.dp, vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            imageUrl = convo.contact.knsAvatarUrl,
            backupPhotoBase64 = convo.contact.backupPhotoBase64,
            fallbackText = convo.contact.avatarFallbackText,
            size = 50.dp,
            address = convo.contact.id
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // ContactsManager.displayName's order, as iOS 9ba6c8c: the name you gave the chat,
            // else its Address Book name, else who it is on chain, else the short address. The
            // contact is the live database row (a rename shows at once), and the Address Book and
            // .kachat reads are snapshot state, so the row redraws when either changes.
            val contactLabel = convo.contact.displayName
            // The chat with yourself is named for what it is; who you are on chain is the chat
            // HEADER's job (the .kachat domain, else the address - addressDisplayName; iOS 7d11735).
            val rowTitle = if (convo.contact.id.equals(myAddress, ignoreCase = true)) stringResource(R.string.your_address) else contactLabel
            Row(verticalAlignment = Alignment.CenterVertically) {
              // The name (and the silenced mark) take every bit the date doesn't need.
              Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rowTitle,
                    // iOS .headline: 17pt semibold
                    fontSize = 17.sp,
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Silenced: no notification from this chat, ever. Worth a mark on the row - a
                // chat that never pings otherwise looks like a chat nobody is using.
                if (com.kachat.app.models.ContactNotificationMode.fromName(convo.contact.notificationOverride) ==
                    com.kachat.app.models.ContactNotificationMode.OFF
                ) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = "Silenced",
                        tint = LocalAppColors.current.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
              }
                // When the last message came: the time today, "Yesterday", else the day (iOS
                // formatDate - caption, secondary).
                convo.lastMessage?.let { last ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = chatListDate(last.blockTimestamp),
                        fontSize = 12.sp,
                        color = LocalAppColors.current.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // A reaction more recent than the last message gets shown instead - reactions never
            // become messages (they're applied as a corner pill), so without this the preview
            // would silently show a stale last message even when the truly most recent activity
            // was someone reacting to something older.
            val reactionPreview = latestReaction?.let { reaction ->
                if (convo.lastMessage != null && convo.lastMessage.blockTimestamp >= reaction.blockTimestamp) {
                    return@let null
                }
                val reactedByMe = reaction.reactorAddress == myAddress
                val targetIsMine = reaction.targetDirection == "sent"
                when {
                    reactedByMe && targetIsMine -> "You reacted to your message"
                    reactedByMe -> "You reacted to their message"
                    targetIsMine -> "Reacted to your message"
                    else -> "Reacted to their message"
                }
            }
            // Computed once per message, not once per recomposition. The chat list re-renders on
            // every sync tick, and this walks the message body looking for a reply envelope and a
            // media mime - cheap now, but not free, and there are as many of these as there are
            // visible rows.
            val previewContext = LocalContext.current
            val preview = remember(convo.lastMessage?.id, contactLabel) {
                messagePreviewText(convo.lastMessage, contactLabel, previewContext)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The row said nothing about whether your own last message went out. iOS's list
                // has carried this since bb1f9f5; Android's never had it at all. Compact
                // rendering: a bare check in the secondary colour, not the bubble's green circle.
                val lastMessage = convo.lastMessage
                if (lastMessage != null && (lastMessage.direction == "sent" || lastMessage.deliveryStatus == "warning")) {
                    DeliveryStatusLabel(
                        status = deliveryStatusOf(lastMessage.deliveryStatus),
                        compact = true,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = when {
                        reactionPreview != null -> reactionPreview
                        convo.contact.conversationStatus == "pending" -> "🤝 ${preview ?: "Wants to connect"}"
                        else -> preview ?: "No messages yet"
                    },
                    // iOS .subheadline: 15pt
                    fontSize = 15.sp,
                    color = if (convo.contact.conversationStatus == "pending") KaspaTeal else LocalAppColors.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (convo.unreadCount > 0) {
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    // iOS: caption2 bold white on the accent, 6 by 2 padding, a capsule.
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 18.dp)
                            .background(KaspaTeal, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = convo.unreadCount.toString(),
                            // See the group row's badge: white on teal in either theme, as on iOS.
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/** The chat list's date (iOS ConversationRow.formatDate): "h:mm a" today, "Yesterday", else
 *  "MMM d". [timestampMs] is the message's time in milliseconds. */
internal fun chatListDate(timestampMs: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(then.time)
    val yesterday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    if (yesterday.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        yesterday.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    ) return "Yesterday"
    return java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(then.time)
}

/**
 * Contact avatar — the single place the app's avatar resolution order lives, used everywhere a
 * contact is shown:
 *
 *   1. [imageUrl] — the contact's KNS profile photo (a remote https URL), when they have one.
 *   2. [backupPhotoBase64] — the photo carried in the chat backup (e.g. one set on desktop).
 *   3. the person glyph.
 *
 * KaChat no longer reads the phone's Contacts (iOS 00767a4), so there is no Contacts-app photo.
 * The image goes through Coil (memory/disk caches, off-main-thread decode); one that fails to
 * load falls through to the backup photo rather than dead-ending on the glyph.
 *
 * On testnet identity is `.kachat`: for an [address] the first step is its `.kachat` avatar (from
 * its profile's social link, looked up on this device), never the KNS [imageUrl] - one change for
 * the chat list, chat header, groups, public chats, calls, chess and contacts (iOS e52357d
 * `KNSAvatarView`). Mainnet, or no [address]: [imageUrl] as before.
 */
@Composable
fun ContactAvatar(
    imageUrl: String?,
    fallbackText: String,
    size: Dp,
    modifier: Modifier = Modifier,
    backgroundColor: Color = LocalAppColors.current.surface,
    fontSize: TextUnit = 16.sp,
    backupPhotoBase64: String? = null,
    /** Whose avatar this is (a `kaspa:` / `kaspatest:` address), for the testnet `.kachat` rule. */
    address: String? = null
) {
    val shownUrl = if (address != null && com.kachat.app.services.kachatnames.KachatNamesService.isEnabled) {
        kachatAvatarUrl(address)
    } else {
        imageUrl
    }
    val candidates = remember(shownUrl) {
        listOfNotNull(shownUrl?.takeIf { it.isNotBlank() })
    }
    // Cross-platform backup photo (base64 JPEG); the last fallback before the glyph. Decoded off
    // the main thread and kept app-wide, so opening a list decodes each photo once rather than
    // every visible row on the main thread every time the screen comes back.
    val backupBitmap by produceState(
        initialValue = backupPhotoBase64?.let { backupAvatarCache.get(it) },
        backupPhotoBase64
    ) {
        val base64 = backupPhotoBase64
        if (base64.isNullOrBlank()) { value = null; return@produceState }
        backupAvatarCache.get(base64)?.let { value = it; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decodeBase64Avatar(base64) }
            ?.also { backupAvatarCache.put(base64, it) }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        AvatarImageChain(candidates, fallbackText, fontSize, backupBitmap)
    }
}

/**
 * [address]'s `.kachat` avatar - its profile's avatar link (the registry's cached identity) as the
 * platform shows it (the social image cache, looked up when missing or stale). Re-renders when
 * either answer lands. Null when it has none (iOS e52357d; mainnet too since d36fc42, where the
 * identity is profile-only).
 */
@Composable
fun kachatAvatarUrl(address: String): String? {
    val resolver = com.kachat.app.services.kachatnames.KachatSocialImageResolver.instance ?: return null
    val entries by resolver.entries.collectAsState()
    val link = com.kachat.app.services.kachatnames.KachatNamesRegistry.cachedIdentityOf(address)?.profile?.avatar
    LaunchedEffect(link) { resolver.refreshIfStale(link) }
    return resolver.cached(link, entries)?.avatar
}

/**
 * [address]'s `.kachat` profile banner and bio (first, second), each looked up from its profile's
 * social link on this device - every network, as profiles aren't registry data (iOS 3d6fb7c
 * `KachatLive.profileBanner` / `profileBio`). Re-renders when the identity or a lookup lands.
 */
@Composable
fun kachatProfileBannerAndBio(address: String): Pair<String?, String?> {
    if (address.isEmpty()) return null to null
    val resolver = com.kachat.app.services.kachatnames.KachatSocialImageResolver.instance ?: return null to null
    val entries by resolver.entries.collectAsState()
    val profile = com.kachat.app.services.kachatnames.KachatNamesRegistry.cachedIdentityOf(address)?.profile
    LaunchedEffect(profile?.banner, profile?.bio) {
        resolver.refreshIfStale(profile?.banner)
        resolver.refreshIfStale(profile?.bio)
    }
    return resolver.cached(profile?.banner, entries)?.banner to resolver.cached(profile?.bio, entries)?.bio
}

private val backupAvatarCache = android.util.LruCache<String, ImageBitmap>(64)

private fun decodeBase64Avatar(base64: String?): ImageBitmap? {
    if (base64.isNullOrBlank()) return null
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        // Backup photos arrive in other devices' archives; the claimed size is not trusted.
        com.kachat.app.util.SafeBitmapDecode.decode(bytes, maxDimension = 512)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * Renders [candidates] in order, dropping to the next on load failure; then the backup photo, then
 * the glyph. A plain AsyncImage over the glyph rather than SubcomposeAsyncImage: subcomposition per
 * avatar is what Coil advises against in scrolling lists, and every list in the app draws these.
 */
@Composable
private fun AvatarImageChain(candidates: List<String>, fallbackText: String, fontSize: TextUnit, backupBitmap: ImageBitmap? = null) {
    var failedCount by remember(candidates) { mutableIntStateOf(0) }
    val current = candidates.getOrNull(failedCount)
    // A new request once User Info > Rescan Profile downloaded this picture again (iOS bc95d17).
    val model = rememberProfileImageModel(current)
    if (current == null) {
        if (backupBitmap != null) {
            Image(
                bitmap = backupBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            AvatarInitials(fallbackText, fontSize)
        }
        return
    }
    var loaded by remember(current) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The glyph shows while the image loads, as the loading slot did before.
        if (!loaded) AvatarInitials(fallbackText, fontSize)
        coil.compose.AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            onError = { failedCount++ }
        )
    }
}

@Composable
private fun AvatarInitials(text: String, fontSize: TextUnit) {
    // 4.0 (matches iOS): no photo shows a person glyph, not initials - initials read like
    // random letters for KNS-less addresses and looked inconsistent next to real avatars.
    // iOS KNSAvatarView.fallbackAvatar: a filled person on the accent at 20%.
    Box(
        modifier = Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = KaspaTeal,
            modifier = Modifier.fillMaxSize(0.5f)
        )
    }
}

// MARK: - Message Requests (NO_HANDSHAKE_MESSAGING.md, iOS f7ca401 / 74bd52c)

/** The chat list's Message Requests row: everyone who wrote first and hasn't been accepted. With
 *  nothing pending it reads "No new requests" and drops the count badge. */
@Composable
private fun MessageRequestsRow(count: Int, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // iOS: 6 of its own plus the List's row insets - a little taller than a chat row.
            .padding(horizontal = 16.dp, vertical = 21.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(50.dp).clip(CircleShape).background(KaspaTeal),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MoveToInbox, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(R.string.message_requests),
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                stringResource(if (count > 0) R.string.message_requests_row_sub else R.string.message_requests_none_new),
                fontSize = 15.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(KaspaTeal)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(count.toString(), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        com.kachat.app.ui.screens.IosDisclosureChevron()
    }
}

/**
 * Chats someone else started that you haven't accepted. Open one to read everything they sent,
 * then Accept or Reject from inside it. Their messages never notify you beyond the first "New
 * message request". iOS `MessageRequestsView` - a sheet with Done, here its own page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageRequestsScreen(
    navController: NavController,
    chatViewModel: ChatViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val requests by chatViewModel.messageRequests.collectAsState()
    val latestReactionByContact by chatViewModel.latestReactionByContact.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.message_requests), color = colors.textPrimary, fontWeight = FontWeight.SemiBold) },
                actions = {
                    SheetBarButton(stringResource(R.string.done), onClick = { navController.popBackStack() }, modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        if (requests.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.Inbox, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.message_requests_empty), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(colors.surface)
                    ) {
                        requests.forEachIndexed { index, convo ->
                            if (index > 0) {
                                HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 80.dp))
                            }
                            ConversationRow(convo, latestReactionByContact[convo.contact.id], myAddress) {
                                navController.navigate("chat/${convo.contact.id}")
                            }
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.message_requests_footer),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** The QR pages the Chats New sheet opens (iOS e6400d6 `ChatsQRScreen`). */
private enum class ChatsNewQr { FUND_CHATTING, RECEIVE }

/** What the Chats New sheet shows: its menu, the room name pushed inside it, or a create screen
 *  swapped in (iOS be0857a / e6400d6). */
private enum class ChatsNewPage { MENU, JOIN_ROOM, NEW_CHAT, NEW_GROUP }

/**
 * The Chats screen's New sheet (iOS 5da8ccf, then be0857a, f508292, 231c05e, e6400d6, f81e8d6
 * and c6851d6 - this is where they ended): a half sheet of square tiles - New Chat, New Group
 * Chat, New Public Chat, Send Kaspa, Receive Kaspa, Request Payment and Fund Chatting Address. Everything but the send
 * and the QR codes happens in this one sheet, so there is no close-then-open wait:
 * - New Chat / New Group Chat swap the sheet to the create screen, full height ([CreateChatScreen],
 *   whose Cancel comes back to the menu);
 * - New Public Chat pushes a room-name field inside the sheet, at the menu's height; joining opens
 *   the room, and an invalid name says why in place;
 * - Send Kaspa closes the sheet and then opens Profile's send from the current spending address
 *   ([onSendKaspa]);
 * - Fund Chatting Address / Receive Kaspa close the sheet and then bring up Profile's full white QR
 *   page ([onShowQr]) - a white page inside a dark sheet never fit (231c05e, undone by e6400d6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsNewSheet(
    chatViewModel: ChatViewModel,
    broadcastViewModel: com.kachat.app.viewmodels.BroadcastViewModel,
    onDismiss: () -> Unit,
    onChatCreated: (String) -> Unit,
    onGroupCreated: (String) -> Unit,
    onRoomJoined: (String) -> Unit,
    onShowQr: (ChatsNewQr) -> Unit,
    onSendKaspa: () -> Unit,
    onRequestPayment: () -> Unit,
) {
    val colors = LocalAppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(ChatsNewPage.MENU) }
    var roomName by remember { mutableStateOf("") }
    /** A join this sheet asked for, so a success elsewhere (the rooms page) is not taken for it. */
    var joining by remember { mutableStateOf<String?>(null) }
    val joinState by broadcastViewModel.joinChannelState.collectAsState()
    val haptic = com.kachat.app.util.rememberHaptics()

    /** Closes the sheet and runs [then] once it has gone (iOS `closeCreateSheet(then:)`). */
    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }

    LaunchedEffect(joinState) {
        val name = joining ?: return@LaunchedEffect
        when (joinState.status) {
            com.kachat.app.viewmodels.BroadcastViewModel.JoinChannelStatus.SUCCESS -> {
                joining = null
                haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                close { onRoomJoined(name) }
            }
            com.kachat.app.viewmodels.BroadcastViewModel.JoinChannelStatus.FAILED -> joining = null
            else -> Unit
        }
    }

    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        // On the menu and Join Room the sheet rests on iOS's 498 detent, where iOS 26 floats it;
        // New Chat / New Group switch to .large, which runs edge to edge.
        val floating = page == ChatsNewPage.MENU || page == ChatsNewPage.JOIN_ROOM
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            modifier = if (floating) Modifier.iosFloatingSheet() else Modifier,
            shape = if (floating) IosFloatingSheetShape else com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = colors.background,
            // iOS's grabber is drawn over the sheet, not in a row above it: the menu is iOS's 380
            // tall in all (createSheetHeight), with "New" 20 below the top edge.
            dragHandle = null,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Box {
                when (page) {
                    // Below the grabber, as iOS's navigation bar sits in a sheet.
                    ChatsNewPage.NEW_CHAT, ChatsNewPage.NEW_GROUP -> Box(Modifier.fillMaxHeight().padding(top = 10.dp)) {
                        CreateChatScreen(
                            onBack = { close() },
                            onCancel = { page = ChatsNewPage.MENU },
                            onChatCreated = { address -> close { onChatCreated(address) } },
                            onGroupCreated = { groupId -> close { onGroupCreated(groupId) } },
                            startInGroupMode = page == ChatsNewPage.NEW_GROUP,
                            chatViewModel = chatViewModel,
                        )
                    }
                    ChatsNewPage.JOIN_ROOM -> ChatsNewJoinRoom(
                        name = roomName,
                        onNameChange = {
                            roomName = it
                            if (joinState.status != com.kachat.app.viewmodels.BroadcastViewModel.JoinChannelStatus.IDLE) {
                                broadcastViewModel.resetJoinChannelState()
                            }
                        },
                        error = joinState.message?.takeIf { joinState.status == com.kachat.app.viewmodels.BroadcastViewModel.JoinChannelStatus.FAILED },
                        onBack = { page = ChatsNewPage.MENU },
                        onJoin = {
                            if (roomName.isNotBlank()) {
                                joining = com.kachat.app.util.MessageProtocol.normalizeChannelName(roomName)
                                broadcastViewModel.joinChannel(roomName)
                            }
                        },
                    )
                    // The New options as square tiles, three to a row (iOS f81e8d6): New Chat, New Group
                    // Chat, New Public Chat / Send Kaspa, Receive Kaspa, Fund Chatting Address. Each keeps
                    // its old line as the TalkBack hint.
                    ChatsNewPage.MENU -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ChatsNewSheetHeight)
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.chats_new),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                        // 104 dp tiles as on iOS, smaller only where three of them do not fit across.
                        BoxWithConstraints(contentAlignment = Alignment.Center) {
                            val tileSize = minOf(ChatsNewTileSize, (maxWidth - ChatsNewTileSpacing * 2) / 3)
                            Column(verticalArrangement = Arrangement.spacedBy(ChatsNewTileSpacing)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(ChatsNewTileSpacing)) {
                                    ChatsNewTile(
                                        size = tileSize,
                                        icon = androidx.compose.material.icons.Icons.Outlined.ChatBubbleOutline,
                                        title = stringResource(R.string.chats_new_chat),
                                        hint = stringResource(R.string.chats_new_chat_subtitle),
                                    ) { page = ChatsNewPage.NEW_CHAT }
                                    ChatsNewTile(
                                        size = tileSize,
                                        icon = Icons.Default.Groups,
                                        title = stringResource(R.string.chats_new_group_chat),
                                        hint = stringResource(R.string.chats_new_group_subtitle),
                                    ) { page = ChatsNewPage.NEW_GROUP }
                                    ChatsNewTile(
                                        size = tileSize,
                                        icon = Icons.Default.Tag,
                                        title = stringResource(R.string.chats_new_public_chat),
                                        hint = stringResource(R.string.chats_new_public_subtitle),
                                    ) {
                                        roomName = ""
                                        joining = null
                                        broadcastViewModel.resetJoinChannelState()
                                        page = ChatsNewPage.JOIN_ROOM
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(ChatsNewTileSpacing)) {
                                    ChatsNewTile(
                                        size = tileSize,
                                        icon = Icons.Outlined.ArrowCircleUp,
                                        title = stringResource(R.string.chats_send_kaspa),
                                        hint = stringResource(R.string.chats_send_kaspa_hint),
                                    ) { close { onSendKaspa() } }
                                    ChatsNewTile(
                                        size = tileSize,
                                        icon = Icons.Outlined.ArrowCircleDown,
                                        title = stringResource(R.string.receive_kaspa),
                                        hint = stringResource(R.string.chats_receive_subtitle),
                                    ) { close { onShowQr(ChatsNewQr.RECEIVE) } }
                                    // A link asking for an exact amount (iOS c6851d6).
                                    ChatsNewTile(
                                        size = tileSize,
                                        icon = RequestPaymentIcon,
                                        title = stringResource(R.string.pr_request_payment),
                                        hint = stringResource(R.string.pr_request_payment_hint),
                                    ) { close { onRequestPayment() } }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(ChatsNewTileSpacing)) {
                                    ChatsNewTile(
                                        size = tileSize,
                                        icon = Icons.Default.QrCode,
                                        title = stringResource(R.string.chatting_address),
                                        hint = stringResource(R.string.chats_fund_subtitle),
                                    ) { close { onShowQr(ChatsNewQr.FUND_CHATTING) } }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/** One square option in the Chats New sheet: an icon over a two-line title, on glass. Its old
 *  explanatory line is the TalkBack hint (iOS f81e8d6 `CreateTile`). */
@Composable
private fun ChatsNewTile(size: Dp, icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(size)
            .sendKaspaGlass(18.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title. $hint" }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(28.dp))
        Text(
            title,
            color = LocalAppColors.current.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val ChatsNewTileSize = 104.dp
private val ChatsNewTileSpacing = 14.dp

/**
 * The New sheet's "Send Kaspa" (iOS 6333dd3): Send Kaspa ([SpendingAddressSendFlow]) in a sheet of
 * its own, as iOS presents it over Chats once the New sheet has gone, with Cancel leading its bar,
 * which only Cancel closes. It starts from the chatting address, and From picks any other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsSpendingSendLauncher(walletViewModel: WalletViewModel, onDone: () -> Unit) {
    val chattingAddress by walletViewModel.address.collectAsState()
    val chattingBalance by walletViewModel.balanceSompi.collectAsState()
    val from = chattingAddress
    if (from == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    IosFullSheet(onDismissed = onDone, swipeToDismiss = false) { close ->
        SpendingAddressSendFlow(
            fromAddress = from,
            balanceSompi = chattingBalance,
            title = stringResource(R.string.chats_send_kaspa),
            viewModel = walletViewModel,
            onDone = close,
            presentedAsSheet = true,
        )
    }
}

/** The menu and the room name share one height, so moving between them is a plain push; the
 *  create screens open at full height. Fits the tiles grid, three rows since Request Payment
 *  (iOS c6851d6 `createSheetHeight`, 498 pt). */
private val ChatsNewSheetHeight = 498.dp

/**
 * Join or create a public room, right in the New sheet (iOS be0857a `createJoinRoom`): the same
 * rules as the rooms page's own join sheet, with the error in place.
 */
@Composable
private fun ChatsNewJoinRoom(
    name: String,
    onNameChange: (String) -> Unit,
    error: String?,
    onBack: () -> Unit,
    onJoin: () -> Unit,
) {
    val colors = LocalAppColors.current
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // A turn later: the field doesn't exist yet on the push that showed it.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChatsNewSheetHeight)
            .padding(bottom = 24.dp),
    ) {
        // The pushed page's bar: back to the menu, and its title.
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            SheetBarBackButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
            Text(
                stringResource(R.string.chats_new_public_chat),
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.anyone_who_joins_the_same_channel),
                color = colors.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                TextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = { Text(stringResource(R.string.channel_name), color = colors.textTertiary) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onJoin() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = KaspaTeal,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                )
            }
            if (error != null) {
                Text(error, color = colors.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            val empty = name.isBlank()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(KaspaTeal.copy(alpha = if (empty) 0.4f else 1f))
                    .clickable(enabled = !empty) { onJoin() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.join), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
