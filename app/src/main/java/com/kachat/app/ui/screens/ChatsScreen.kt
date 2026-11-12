package com.kachat.app.ui.screens

import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.models.avatarFallbackText
import com.kachat.app.models.displayName
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.MessageReply
import com.kachat.app.util.findFragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.launch

/** One row of the Chats list (iOS d1844582 `ChatListEntry`): chats and groups share it, by latest
 *  activity. [id] is what Select mode tracks. */
private sealed interface ChatListEntry {
    val id: String

    data class Chat(val convo: Conversation) : ChatListEntry {
        override val id get() = "c:${convo.contact.id}"
    }

    data class Group(val convo: com.kachat.app.repository.GroupConversation) : ChatListEntry {
        override val id get() = "g:${convo.group.groupId}"
    }
}

/**
 * Chats tab: one list (iOS d1844582). The chats and groups by latest activity, with every bot chat
 * as a circle above them ([ChatCirclesStrip]) under the search bar - swipe sideways for all of them.
 * Pinned chats and groups move up into that row.
 *
 * Select mode selects chats and groups; Select All takes everything visible, and mark read / mark
 * unread / delete act on the whole selection.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    // The chat list leaves out Message Requests and blocked chats - requests sit behind their own
    // row, and neither counts toward the unread badge (iOS f7ca401, 84e3402).
    val chatListConversations by chatViewModel.chatListConversations.collectAsState()
    // Bot chats are never rows in the chat list (or its search): every bot is always a circle in
    // the row above it (iOS d54ea87).
    val conversations = remember(chatListConversations) {
        chatListConversations.filterNot { com.kachat.app.models.KaChatBot.isBot(it.contact.id) }
    }
    val botCircles by chatViewModel.botCircles.collectAsState()
    val groupConversations by chatViewModel.groupConversations.collectAsState()
    val messageRequests by chatViewModel.messageRequests.collectAsState()
    // Groups you were added to wait in Message Requests too (iOS bfe9f022).
    val pendingGroupInvites by chatViewModel.pendingGroupInvites.collectAsState()
    MessageRequestsDeepLinkEffect(navController)
    val latestReactionByContact by chatViewModel.latestReactionByContact.collectAsState()
    val chatFetchStates by chatViewModel.chatFetchStates.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    val isRefreshingChats by chatViewModel.isRefreshing.collectAsState()
    val isRefreshingGroups by chatViewModel.isRefreshingGroups.collectAsState()
    val isRefreshing = isRefreshingChats || isRefreshingGroups
    var searchQuery by remember { mutableStateOf("") }
    var isSelectionMode by remember { mutableStateOf(false) }
    /** Select mode's chats and groups, as [ChatListEntry.id]s. */
    var selectedEntryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkDeleteConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    // The bottom-right + : New Chat or New Group Chat, in one sheet (iOS d1844582).
    var showNewSheet by remember { mutableStateOf(false) }

    // What is pinned in the circles row, newest pin first, saved per wallet: bots ("b:<address>")
    // pinned to its front, and chats ("c:<address>") pinned up out of the list into the row (iOS
    // 7abc06e). The key once held group circles too, as "g:<groupId>"; groupPins below took those
    // over and the row ignores them (iOS a53d343).
    val circlePins by remember(myAddress) {
        myAddress?.let { chatViewModel.chatCirclePins(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    // Group ids pinned up into the same row, newest pin first, saved per wallet (iOS d1844582).
    val groupPins by remember(myAddress) {
        myAddress?.let { chatViewModel.groupPins(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    /** The pinned group whose long-press half sheet is up (list rows swipe instead). */
    var groupActionTarget by remember { mutableStateOf<String?>(null) }
    /** A group waiting on its delete confirmation. */
    var groupDeleteTarget by remember { mutableStateOf<String?>(null) }
    /** The circle whose long-press half sheet is up ("b:<address>", iOS f90a70a). */
    var circleActionTarget by remember { mutableStateOf<String?>(null) }
    /** A bot chat waiting on its delete confirmation, from its circle's sheet (iOS 5fcf755). */
    var circleDeleteBot by remember { mutableStateOf<com.kachat.app.models.ContactEntity?>(null) }
    /** Pins a bot to the front of the row or a chat up into it (newest pin first), or unpins it,
     *  with a toast saying which (iOS `toggleCirclePin`). */
    fun toggleCirclePin(id: String) {
        val address = myAddress ?: return
        scope.launch {
            val pinned = chatViewModel.toggleChatCirclePin(address, id)
            IosToasts.show(context.getString(when {
                !pinned -> R.string.chats_unpinned
                id.startsWith("c:") -> R.string.pinned_to_top_row
                else -> R.string.chats_pinned_to_front
            }))
        }
    }
    /** Deleted chats leave the row too (iOS 7abc06e). */
    fun unpinDeletedChats(contactIds: Collection<String>) {
        val address = myAddress ?: return
        scope.launch { chatViewModel.unpinChatCircles(address, contactIds.map { chatPinId(it) }) }
    }
    /** The one delete path for chats - bulk, a row's swipe, a bot's half sheet - ending in one
     *  toast (iOS `deleteConversations`). Your own chat is never deleted, even in a Select All. */
    fun deleteChats(contactIds: Collection<String>) {
        val ids = contactIds.filterNot { it.equals(myAddress, ignoreCase = true) }
        if (ids.isEmpty()) return
        chatViewModel.deleteChats(ids)
        unpinDeletedChats(ids)
        IosToasts.show(if (ids.size == 1) context.getString(R.string.chat_deleted) else context.getString(R.string.n_chats_deleted, ids.size))
    }
    /** Pins a group up into the row (newest pin first), or unpins it (iOS d1844582 `toggleGroupPin`). */
    fun toggleGroupPin(groupId: String) {
        val address = myAddress ?: return
        scope.launch {
            val pinned = chatViewModel.toggleGroupPin(address, groupId)
            IosToasts.show(context.getString(if (pinned) R.string.pinned_to_top_row else R.string.chats_unpinned))
        }
    }
    fun deleteGroups(ids: Set<String>) {
        if (ids.isEmpty()) return
        chatViewModel.deleteGroupChats(ids)
        myAddress?.let { address -> scope.launch { chatViewModel.unpinGroups(address, ids) } }
        selectedEntryIds = selectedEntryIds - ids.map { "g:$it" }.toSet()
        IosToasts.show(if (ids.size == 1) context.getString(R.string.group_deleted) else context.getString(R.string.n_groups_deleted, ids.size))
    }

    // iOS ChatListView's search: the chat's display name (the name you gave it, else its Address
    // Book name, else who it is on chain, else the short address - for your own chat, only who
    // you are on chain), the raw address (so pasting part of an address you recognize still
    // finds it), or any of its messages. The messages are read from the database off the main
    // thread; until that answers the previous answer stays.
    val messageMatchIds by produceState<Set<String>>(initialValue = emptySet(), searchQuery, conversations) {
        value = chatViewModel.contactIdsWithMessageMatching(searchQuery)
    }
    // The chats pinned up into the circles row, by pin id. Empty while searching: the results are
    // the whole match, pinned or not (iOS 7abc06e).
    val pinnedChats = remember(conversations, circlePins, searchQuery) {
        if (searchQuery.isNotEmpty()) emptyMap()
        else {
            val ids = circlePins.filter { it.startsWith("c:") }.toSet()
            if (ids.isEmpty()) emptyMap() else conversations.filter { chatPinId(it.contact.id) in ids }.associateBy { chatPinId(it.contact.id) }
        }
    }
    val filteredConversations = remember(conversations, searchQuery, messageMatchIds, pinnedChats) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            // Pinned chats live in the circles row, not the list.
            if (pinnedChats.isEmpty()) conversations else conversations.filter { chatPinId(it.contact.id) !in pinnedChats }
        } else {
            conversations.filter { convo ->
                convo.contact.displayName.contains(query, ignoreCase = true) ||
                    convo.contact.id.contains(query, ignoreCase = true) ||
                    convo.contact.id in messageMatchIds
            }
        }
    }

    // Filtered by search on the group name, each member's name or address, and message content;
    // until the search answers, the previous answer stays.
    val searchedGroupIds by produceState<Set<String>?>(initialValue = null, groupConversations, searchQuery) {
        value = if (searchQuery.isBlank()) null
        else chatViewModel.groupIdsMatchingSearch(groupConversations.map { it.group }, searchQuery)
    }
    // The pinned groups, in pin order (newest pin first). Empty while searching: the results list
    // them with the rest.
    val pinnedGroups = remember(groupConversations, groupPins, searchQuery) {
        if (searchQuery.isNotBlank()) emptyList()
        else {
            val byId = groupConversations.associateBy { it.group.groupId }
            groupPins.mapNotNull { byId[it] }
        }
    }
    // The unpinned groups; while searching, every matching group, pinned or not.
    val displayedGroups = remember(groupConversations, groupPins, searchedGroupIds, searchQuery) {
        val matches = searchedGroupIds
        when {
            searchQuery.isBlank() -> groupConversations.filter { it.group.groupId !in groupPins }
            matches == null -> emptyList()
            else -> groupConversations.filter { it.group.groupId in matches }
        }
    }
    // The chats and groups, most recent message first (a group with none yet by when it was added).
    // Your own chat takes its place by date like any other (iOS d1844582 removed `pinningSelfChat`).
    // Shared by Select All and the bulk actions so they agree on what's visible.
    val allEntries = remember(filteredConversations, displayedGroups) {
        buildList<Pair<Long, ChatListEntry>> {
            filteredConversations.forEach { add((it.lastMessage?.blockTimestamp ?: 0L) to ChatListEntry.Chat(it)) }
            displayedGroups.forEach { add((it.lastMessage?.blockTimestamp ?: it.group.createdAt) to ChatListEntry.Group(it)) }
        }.sortedByDescending { it.first }.map { it.second }
    }

    // The circles, as the row shows them: pinned first, then by latest activity, filtered by the
    // search.
    val circleItems = remember(botCircles, pinnedChats, searchQuery, circlePins, myAddress) {
        chatCircleItems(
            bots = botCircles,
            pinnedChats = pinnedChats,
            searchQuery = searchQuery,
            pins = circlePins,
            botTitle = { bot -> context.getString(bot.nameRes) },
            // As its list row names it.
            chatTitle = { convo ->
                if (convo.contact.id.equals(myAddress, ignoreCase = true)) context.getString(R.string.your_address)
                else convo.contact.displayName
            },
        )
    }
    val selectionCount = selectedEntryIds.size
    val isEverythingSelected = allEntries.isNotEmpty() && selectionCount >= allEntries.size
    /** The contact ids among the selection. */
    fun selectedChatIds(): Set<String> =
        selectedEntryIds.filter { it.startsWith("c:") }.map { it.removePrefix("c:") }.toSet()
    /** The group ids among the selection. */
    fun selectedGroupIds(): Set<String> =
        selectedEntryIds.filter { it.startsWith("g:") }.map { it.removePrefix("g:") }.toSet()
    fun endSelection() {
        isSelectionMode = false
        selectedEntryIds = emptySet()
    }
    fun toggleSelection(id: String) {
        selectedEntryIds = if (id in selectedEntryIds) selectedEntryIds - id else selectedEntryIds + id
    }

    // An account switch keeps this screen alive: every account starts on its own list.
    LaunchedEffect(myAddress) {
        endSelection()
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            chatViewModel.refreshChats()
            chatViewModel.refreshGroups()
        }
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

    // Notifications are asked for when this list first appears, and only ever once (iOS asks from
    // its chat list too).
    LaunchedEffect(Unit) {
        (context.findFragmentActivity() as? com.kachat.app.MainActivity)?.maybeRequestNotificationPermission()
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
                    // row on top, the backup warning, bold large "Chats" title below it, search
                    // bar DIRECTLY underneath the title.
                    TopStatusBar(
                        balance = balance,
                        onStatusClick = { ConnectionStatusOverlayState.open() },
                        dotColorHex = dotColorHex,
                        showAddButton = false,
                        showEditButton = true,
                        isEditing = isSelectionMode,
                        onEditClick = {
                            if (isSelectionMode) endSelection() else isSelectionMode = true
                        },
                        selectAllLabel = if (isEverythingSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                        onSelectAllClick = {
                            selectedEntryIds = if (isEverythingSelected) emptySet() else allEntries.map { it.id }.toSet()
                        }
                    )
                }
                AccountBackupBanner(walletViewModel) { navController.navigate("account_backup") }
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
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
            // Mark read, mark unread and delete, for every selected chat and group (iOS
            // `selectionActionBar`). Above the dock, which stays up, as iOS's sits above its tab
            // bar (its `.safeAreaInset(edge: .bottom)`); the dock's inset includes the system bar.
            if (isSelectionMode) {
                Column(
                    modifier = Modifier
                        .background(LocalAppColors.current.background)
                        .then(if (dockInset > 0.dp) Modifier.padding(bottom = dockInset) else Modifier.navigationBarsPadding())
                ) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedChatIds().let { if (it.isNotEmpty()) chatViewModel.markContactsAsRead(it) }
                                selectedGroupIds().let { if (it.isNotEmpty()) chatViewModel.markGroupsAsRead(it) }
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
                                selectedChatIds().let { if (it.isNotEmpty()) chatViewModel.markContactsAsUnread(it) }
                                selectedGroupIds().let { if (it.isNotEmpty()) chatViewModel.markGroupsAsUnread(it) }
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
            // Long-press target - which pinned chat's action sheet is open (list rows swipe instead).
            var menuContactId by remember { mutableStateOf<String?>(null) }
            // The swiped row that is open; the list scrolling closes it, as on iOS.
            var openSwipeRow by remember { mutableStateOf<Any?>(null) }
            val chatListState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(chatListState.isScrollInProgress) {
                if (chatListState.isScrollInProgress) openSwipeRow = null
            }
            // The row's "Add to Address Book" tile: the chat whose entry sheet is up (iOS 98f3728).
            var addressBookTarget by remember { mutableStateOf<com.kachat.app.models.ContactEntity?>(null) }
            val addressBookEntries = rememberAddressBookEntries()

            LazyColumn(
                state = chatListState,
                modifier = Modifier.fillMaxSize().dismissKeyboardOnScroll(),
                // The last row scrolls clear of the dock it runs under (none while selecting:
                // the selection bar sits above the dock and the Scaffold pads the list above it).
                contentPadding = PaddingValues(bottom = if (isSelectionMode) 0.dp else dockInset),
            ) {
                // The bot chats and the chats and groups you pinned, as circles under the search
                // bar - swipe sideways for all of them. Tap opens, hold opens their half sheet.
                item(key = "chat_circles") {
                    ChatCirclesStrip(
                        items = circleItems,
                        pins = circlePins,
                        pinnedGroups = pinnedGroups,
                        isSelectionMode = isSelectionMode,
                        onOpenGroup = { groupId -> navController.navigate("group_chat/$groupId") },
                        onLongPressGroup = { groupId -> groupActionTarget = groupId },
                        onTap = { item ->
                            when (item) {
                                // Opens the bot's chat, starting it the first time (iOS d54ea87).
                                is ChatCircle.Bot -> chatViewModel.openBotChat(item.circle.bot, item.title) { address ->
                                    navController.navigate("chat/$address")
                                }
                                is ChatCircle.Chat -> navController.navigate("chat/${item.convo.contact.id}")
                            }
                        },
                        onLongPress = { item ->
                            when (item) {
                                // A bot's half sheet, like a chat row's (iOS f90a70a).
                                is ChatCircle.Bot -> circleActionTarget = item.id
                                // A pinned chat holds to the chat row's own half sheet (with Unpin).
                                is ChatCircle.Chat -> menuContactId = item.convo.contact.id
                            }
                        },
                    )
                }
                // People who wrote first and haven't been accepted - one row, always there,
                // right above the chats; hidden only while searching or selecting
                // (NO_HANDSHAKE_MESSAGING.md, iOS f7ca401 / 74bd52c).
                if (searchQuery.isBlank() && !isSelectionMode) {
                    item(key = "message_requests_row") {
                        Column {
                            MessageRequestsRow(count = messageRequests.size + pendingGroupInvites.size) {
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
                if (allEntries.isNotEmpty()) {
                    items(allEntries, key = { it.id }) { entry ->
                        when (entry) {
                        is ChatListEntry.Group -> {
                            val convo = entry.convo
                            val groupId = convo.group.groupId
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().background(LocalAppColors.current.background)
                            ) {
                                if (isSelectionMode) {
                                    Icon(
                                        imageVector = if (entry.id in selectedEntryIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = stringResource(R.string.select_group),
                                        tint = if (entry.id in selectedEntryIds) KaspaTeal else LocalAppColors.current.textSecondary,
                                        modifier = Modifier.padding(start = 16.dp).size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    // Swipe left for Delete, Silence and Pin, right for the read state
                                    // (iOS d1844582 groupSwipeLeftActions / groupSwipeRightActions).
                                    val colors = LocalAppColors.current
                                    val silentGroups by chatViewModel.groupSilent.collectAsState()
                                    val isSilent = groupId in silentGroups
                                    val isPinned = groupId in groupPins
                                    SwipeActionsRow(
                                        key = entry.id,
                                        openKey = openSwipeRow,
                                        onOpenKeyChange = { openSwipeRow = it },
                                        enabled = !isSelectionMode,
                                        leadingActions = listOf(
                                            if (convo.unreadCount > 0) {
                                                SwipeAction(Icons.Outlined.MarkEmailRead, stringResource(R.string.mark_as_read), KaspaTeal) {
                                                    chatViewModel.markGroupsAsRead(listOf(groupId))
                                                }
                                            } else {
                                                SwipeAction(Icons.Outlined.MarkEmailUnread, stringResource(R.string.mark_as_unread), KaspaTeal) {
                                                    chatViewModel.markGroupsAsUnread(listOf(groupId))
                                                }
                                            }
                                        ),
                                        trailingActions = listOf(
                                            SwipeAction(Icons.Outlined.Delete, stringResource(R.string.delete), colors.danger) {
                                                groupDeleteTarget = groupId
                                            },
                                            SwipeAction(
                                                icon = if (isSilent) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                                                title = if (isSilent) stringResource(R.string.unsilence) else stringResource(R.string.silence),
                                                tint = colors.warning,
                                            ) { chatViewModel.setGroupSilent(groupId, !isSilent) },
                                            SwipeAction(
                                                icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                                title = if (isPinned) stringResource(R.string.chats_circle_unpin) else stringResource(R.string.pin),
                                                tint = KaspaTeal,
                                            ) { toggleGroupPin(groupId) },
                                        ),
                                    ) {
                                        GroupChatRow(
                                            convo = convo,
                                            chatViewModel = chatViewModel,
                                            myAddress = myAddress,
                                        ) {
                                            if (isSelectionMode) {
                                                toggleSelection(entry.id)
                                            } else {
                                                navController.navigate("group_chat/$groupId")
                                            }
                                        }
                                    }
                                    // From the text's start (16 + 50 + 12) to 16 short of the edge.
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                                        color = LocalAppColors.current.textTertiary.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                        is ChatListEntry.Chat -> {
                            val convo = entry.convo
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().background(LocalAppColors.current.background)
                            ) {
                                if (isSelectionMode) {
                                    Icon(
                                        imageVector = if (entry.id in selectedEntryIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = stringResource(R.string.select_chat),
                                        tint = if (entry.id in selectedEntryIds) KaspaTeal else LocalAppColors.current.textSecondary,
                                        modifier = Modifier.padding(start = 16.dp).size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    // Swipe left for Delete, Silence and Pin (your own chat has
                                    // only Silence), right for the read state (iOS d063b897).
                                    val colors = LocalAppColors.current
                                    val isOwnChat = convo.contact.id.equals(myAddress, ignoreCase = true)
                                    val isSilent = com.kachat.app.models.ContactNotificationMode
                                        .fromName(convo.contact.notificationOverride) ==
                                        com.kachat.app.models.ContactNotificationMode.OFF
                                    val pinId = chatPinId(convo.contact.id)
                                    val isPinned = pinId in circlePins
                                    val silenceAction = SwipeAction(
                                        icon = if (isSilent) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                                        title = if (isSilent) stringResource(R.string.unsilence) else stringResource(R.string.silence),
                                        tint = colors.warning,
                                    ) {
                                        chatViewModel.updateContactNotificationOverride(
                                            convo.contact.id,
                                            if (isSilent) null else com.kachat.app.models.ContactNotificationMode.OFF
                                        )
                                    }
                                    SwipeActionsRow(
                                        key = entry.id,
                                        openKey = openSwipeRow,
                                        onOpenKeyChange = { openSwipeRow = it },
                                        enabled = !isSelectionMode,
                                        leadingActions = listOf(
                                            if (convo.unreadCount > 0) {
                                                SwipeAction(Icons.Outlined.MarkEmailRead, stringResource(R.string.mark_as_read), KaspaTeal) {
                                                    chatViewModel.markAsRead(convo.contact.id)
                                                }
                                            } else {
                                                SwipeAction(Icons.Outlined.MarkEmailUnread, stringResource(R.string.mark_as_unread), KaspaTeal) {
                                                    chatViewModel.markAsUnread(convo.contact.id)
                                                }
                                            }
                                        ),
                                        trailingActions = if (isOwnChat) {
                                            listOf(silenceAction)
                                        } else {
                                            listOf(
                                                SwipeAction(Icons.Outlined.Delete, stringResource(R.string.delete), colors.danger) {
                                                    contactToDelete = convo.contact.id
                                                },
                                                silenceAction,
                                                SwipeAction(
                                                    icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                                    title = if (isPinned) stringResource(R.string.chats_circle_unpin) else stringResource(R.string.pin),
                                                    tint = KaspaTeal,
                                                ) { toggleCirclePin(pinId) },
                                            )
                                        },
                                    ) {
                                        ConversationRow(
                                            convo,
                                            latestReactionByContact[convo.contact.id],
                                            myAddress,
                                            chatFetchStates[convo.contact.id],
                                        ) {
                                            if (isSelectionMode) {
                                                toggleSelection(entry.id)
                                            } else {
                                                navController.navigate("chat/${convo.contact.id}")
                                            }
                                        }
                                    }
                                    // iOS 26: from the text's start (16 + 50 + 12) to 16 short of the edge.
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                                        color = LocalAppColors.current.textTertiary.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                        }
                    }
                    item {
                        // The chats and groups listed - while searching, the ones that match (iOS
                        // totalCount).
                        val chatCount = allEntries.size
                        Text(
                            text = if (chatCount == 1) stringResource(R.string.one_chat) else stringResource(R.string.n_chats, chatCount),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    }
                }
            }

            menuContactId?.let { menuId -> conversations.firstOrNull { it.contact.id == menuId } }?.let { convo ->
                // A sheet, not a dropdown: each option carries a line saying
                // what it does (the TalkBack hint, now the options are square
                // tiles - iOS cdac6d0), and Silence needs one - it is not
                // obvious that it overrides the app-wide notification setting.
                val isSilent = com.kachat.app.models.ContactNotificationMode
                    .fromName(convo.contact.notificationOverride) ==
                    com.kachat.app.models.ContactNotificationMode.OFF
                val pinId = chatPinId(convo.contact.id)
                val isPinned = pinId in circlePins
                val isOwn = convo.contact.id.equals(myAddress, ignoreCase = true)
                // As iOS sizes it: your own chat has no Address Book or Delete tile.
                TileActionSheet(
                    title = convo.contact.displayName,
                    onDismiss = { menuContactId = null },
                    height = ActionSheetTileMetrics.sheetHeight(tiles = if (isOwn) 3 else 5),
                ) {
                        if (convo.unreadCount > 0) {
                            ActionSheetRow(
                                icon = Icons.Outlined.MarkEmailRead,
                                title = stringResource(R.string.mark_as_read),
                                subtitle = stringResource(R.string.bot_sheet_clears_unread),
                            ) {
                                menuContactId = null
                                chatViewModel.markAsRead(convo.contact.id)
                            }
                        } else {
                            ActionSheetRow(
                                icon = Icons.Outlined.MarkEmailUnread,
                                title = stringResource(R.string.mark_as_unread),
                                subtitle = stringResource(R.string.puts_the_unread_badge_back_so_you),
                            ) {
                                menuContactId = null
                                chatViewModel.markAsUnread(convo.contact.id)
                            }
                        }
                        // Up into the circles row above the list, out of the list itself (iOS 7abc06e).
                        ActionSheetRow(
                            icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            title = stringResource(if (isPinned) R.string.chats_circle_unpin else R.string.pin_to_top_row),
                            subtitle = stringResource(if (isPinned) R.string.chats_unpin_top_hint else R.string.chats_pin_top_hint),
                        ) {
                            menuContactId = null
                            toggleCirclePin(pinId)
                        }
                        ActionSheetRow(
                            icon = if (isSilent) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                            title = if (isSilent) stringResource(R.string.unsilence) else stringResource(R.string.silence),
                            subtitle = if (isSilent) {
                                stringResource(R.string.notifications_from_this_chat_resume)
                            } else {
                                stringResource(R.string.no_notification_from_this_chat_whatever_your)
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
                                icon = if (saved) ContactsFilledIcon else ContactsIcon,
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
                            subtitle = stringResource(R.string.removes_this_chat_and_its_messages_from),
                            tint = LocalAppColors.current.danger,
                        ) {
                            menuContactId = null
                            contactToDelete = convo.contact.id
                        }
                }
            }

            // Below the circles row rather than over it, and only when there are no chats at all:
            // a search that matches nothing leaves the list empty, as iOS does (ChatListView
            // emptyStateView, centred in the list and 120 lower).
            if (allEntries.isEmpty() && searchQuery.isEmpty() && pinnedChats.isEmpty() && pinnedGroups.isEmpty()) {
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
                // iOS's "Delete Chat?" alert (rowDeleteContact), its title and text as they are there.
                com.kachat.app.ui.theme.IosAlertDialog(
                    onDismissRequest = { contactToDelete = null },
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
                            deleteChats(listOf(contactId))
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

        // One confirmation for the whole selection (iOS `bulkDeleteAlertTitle`).
        if (showBulkDeleteConfirmation) {
            val chats = selectedEntryIds.size
            com.kachat.app.ui.theme.IosAlertDialog(
                onDismissRequest = { showBulkDeleteConfirmation = false },
                containerColor = LocalAppColors.current.surface,
                title = {
                    Text(if (chats == 1) stringResource(R.string.delete_one_chat) else stringResource(R.string.delete_n_chats, chats), color = LocalAppColors.current.textPrimary)
                },
                text = {
                    Text(
                        stringResource(R.string.this_permanently_deletes_every_message_in_each),
                        color = LocalAppColors.current.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteChats(selectedChatIds())
                        deleteGroups(selectedGroupIds())
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

    // A pinned group's hold (iOS d1844582 `groupActionSheet`).
    groupActionTarget?.let { groupId ->
        GroupChatActionSheet(
            groupId = groupId,
            isPinned = groupId in groupPins,
            groupConversations = groupConversations,
            chatViewModel = chatViewModel,
            onDismiss = { groupActionTarget = null },
            onTogglePin = { toggleGroupPin(groupId) },
            onDelete = { groupDeleteTarget = groupId },
        )
    }
    groupDeleteTarget?.let { groupId ->
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { groupDeleteTarget = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.delete_group_2), color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.this_removes_this_group_and_its_messages),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteGroups(setOf(groupId))
                    groupDeleteTarget = null
                }) {
                    Text(stringResource(R.string.delete), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupDeleteTarget = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    circleActionTarget?.let { id ->
        ChatCircleActionSheet(
            id = id,
            isPinned = id in circlePins,
            botCircles = botCircles,
            chatViewModel = chatViewModel,
            onDismiss = { circleActionTarget = null },
            onTogglePin = { toggleCirclePin(id) },
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
                    deleteChats(listOf(contact.id))
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
    // The New sheet (iOS d1844582): New Chat or New Group Chat swaps it to the create screen. Send,
    // Receive and Request are on Wallet.
    if (showNewSheet) {
        ChatsNewSheet(
            chatViewModel = chatViewModel,
            onDismiss = { showNewSheet = false },
            onChatCreated = { address -> navController.navigate("chat/$address") },
            onGroupCreated = { groupId -> navController.navigate("group_chat/$groupId") },
        )
    }
}

/** One circle in [ChatCirclesStrip]: a bot, or a chat pinned up out of the list (iOS 7abc06e
 *  `ChatCircleItem`). [id] is the pin id - "b:<bot address>" / "c:<chat address>", lowercased. */
private sealed interface ChatCircle {
    val id: String
    val title: String
    val unread: Int

    /** A bot, with its chat when there is one ([ChatViewModel.botCircles]); [title] is the
     *  bot's name. */
    data class Bot(val circle: ChatViewModel.BotCircle, override val title: String) : ChatCircle {
        override val id get() = "b:${circle.bot.address.lowercase()}"
        override val unread get() = circle.conversation?.unreadCount ?: 0
    }

    /** A chat pinned up into the row (Pin to Top Row); [title] is its list row's name. */
    data class Chat(override val id: String, val convo: Conversation, override val title: String) : ChatCircle {
        override val unread get() = convo.unreadCount
    }
}

/** A chat's id in the circles row's pins (iOS 7abc06e `chatPinId`). */
internal fun chatPinId(address: String) = "c:${address.lowercase()}"

/**
 * The circles in row order (iOS 7abc06e `ChatCirclesStrip.items`): the pinned ones first, in pin
 * order - bots matching the search, and the chats pinned up out of the list - then the other bots
 * by latest activity (a bot's last message, else never), filtered by the search on their names.
 */
private fun chatCircleItems(
    bots: List<ChatViewModel.BotCircle>,
    pinnedChats: Map<String, Conversation>,
    searchQuery: String,
    pins: List<String>,
    botTitle: (com.kachat.app.models.KaChatBot) -> String,
    chatTitle: (Conversation) -> String,
): List<ChatCircle> {
    val query = searchQuery.trim()
    val botItems = bots.map { ChatCircle.Bot(it, botTitle(it.bot)) }
    fun matches(bot: ChatCircle.Bot) = query.isEmpty() || bot.title.contains(query, ignoreCase = true)
    val botsById = botItems.associateBy { it.id }
    val pinned = pins.mapNotNull { id ->
        botsById[id]?.takeIf(::matches) ?: pinnedChats[id]?.let { ChatCircle.Chat(id, it, chatTitle(it)) }
    }
    val pinIds = pins.toSet()
    val rest = botItems
        .filter { it.id !in pinIds && matches(it) }
        .sortedByDescending { it.circle.conversation?.lastMessage?.blockTimestamp ?: 0L }
    return pinned + rest
}

/**
 * The row of circles above the chats list, under the search bar (iOS 7abc06e `ChatCirclesStrip`):
 * the bot chats and the chats you pinned there (Pin to Top Row on a chat's half sheet). Swipe
 * sideways for all of them. A red count shows unread messages; a pin marks the pinned ones. Tap
 * opens; hold opens the half sheet (a bot's: read state, pin, notifications, delete - iOS f90a70a;
 * a pinned chat's: its chat row's, with Unpin; a pinned group's: [GroupChatActionSheet]). The
 * pinned groups ([pinnedGroups]) come first (iOS d1844582).
 */
@Composable
private fun ChatCirclesStrip(
    items: List<ChatCircle>,
    pins: List<String>,
    pinnedGroups: List<com.kachat.app.repository.GroupConversation>,
    isSelectionMode: Boolean,
    onOpenGroup: (String) -> Unit,
    onLongPressGroup: (String) -> Unit,
    onTap: (ChatCircle) -> Unit,
    onLongPress: (ChatCircle) -> Unit,
) {
    if (items.isEmpty() && pinnedGroups.isEmpty()) {
        Spacer(Modifier.fillMaxWidth().height(1.dp))
        return
    }
    PinnedCirclesRow {
        pinnedGroups.forEach { convo ->
            PinnedCircle(
                name = convo.group.name,
                unreadCount = convo.unreadCount,
                showsPin = true,
                isSelecting = isSelectionMode,
                onTap = { onOpenGroup(convo.group.groupId) },
                onLongPress = { onLongPressGroup(convo.group.groupId) },
            ) { GroupListAvatar(photoHex = convo.group.photoHex, size = 60.dp) }
        }
        items.forEach { item ->
            PinnedCircle(
                name = item.title,
                unreadCount = item.unread,
                showsPin = item is ChatCircle.Chat || item.id in pins,
                isSelecting = isSelectionMode,
                onTap = { onTap(item) },
                onLongPress = { onLongPress(item) },
            ) { ChatCircleAvatar(item) }
        }
    }
}

/** A row of [PinnedCircle]s above the chat list - swipe sideways for all of them. Every circle is
 *  the same height, so the row has one known height. */
@Composable
private fun PinnedCirclesRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

/**
 * One circle in the row above the chat list (iOS 7abc06e `PinnedCircleButton`): the [avatar], the
 * whole name under it, a red unread count and, when [showsPin], a pin. Tap opens; hold opens its half sheet. Dimmed and inert while the list is in
 * Select mode.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PinnedCircle(
    name: String,
    unreadCount: Int,
    showsPin: Boolean,
    isSelecting: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val holdHint = stringResource(R.string.chats_circle_hold_for_options)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(68.dp)
            .alpha(if (isSelecting) 0.55f else 1f)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isSelecting,
                onClick = onTap,
                onLongClick = {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_MEDIUM)
                    onLongPress()
                },
            )
            // Its name, then the hint (iOS: label + "Hold for options").
            .semantics { contentDescription = "$name. $holdHint" },
    ) {
        Box(modifier = Modifier.size(60.dp)) {
            Box(
                modifier = Modifier.size(60.dp).clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) { avatar() }
            if (unreadCount > 0 && !isSelecting) {
                ChatsUnreadBadge(
                    count = unreadCount,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp),
                )
            }
            if (showsPin && !isSelecting) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-2).dp, y = 2.dp)
                        .background(KaspaTeal, CircleShape)
                        .padding(4.dp),
                ) {
                    Icon(Icons.Default.PushPin, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
        }
        // The whole name: a long one carries on underneath (up to three lines) rather than being
        // cut off, centered. Every name reserves the three lines, so every circle is the same
        // height and the row has one known height - nothing for the list below to cut off, and
        // the circles stay level (iOS 7a8ef1f, 55cb8e6).
        Text(
            name,
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

/** A circle's face: a bot's own icon (iOS 5fcf755) on the accent tint; a pinned chat's avatar. */
@Composable
private fun ChatCircleAvatar(item: ChatCircle) {
    when (item) {
        is ChatCircle.Bot -> Box(
            modifier = Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                item.circle.bot.icon,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier.size(26.dp),
            )
        }
        is ChatCircle.Chat -> ContactAvatar(
            imageUrl = item.convo.contact.knsAvatarUrl,
            backupPhotoBase64 = item.convo.contact.backupPhotoBase64,
            fallbackText = item.convo.contact.avatarFallbackText,
            size = 60.dp,
            address = item.convo.contact.id,
        )
    }
}

/**
 * A bot circle's long-press half sheet - the chat row's options (iOS f90a70a `circleActionSheet`):
 * read state, pin to the front, notifications and delete, confirmed first ([onDeleteBot]). As
 * square tiles, like every long-press menu (iOS cdac6d0).
 */
@Composable
private fun ChatCircleActionSheet(
    id: String,
    isPinned: Boolean,
    botCircles: List<ChatViewModel.BotCircle>,
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleteBot: (com.kachat.app.models.ContactEntity) -> Unit,
) {
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

    if (id.startsWith("b:")) {
        // A bot's circle (iOS 5fcf755, d54ea87): read state, pin to the front, Silence, and
        // Delete - confirmed; the bot stays in the row. A bot never written to has only its pin.
        val circle = botCircles.firstOrNull { "b:${it.bot.address.lowercase()}" == id } ?: return
        val convo = circle.conversation
        val contact = convo?.contact
        val isSilent = contact != null &&
            com.kachat.app.models.ContactNotificationMode.fromName(contact.notificationOverride) ==
            com.kachat.app.models.ContactNotificationMode.OFF
        val title = stringResource(circle.bot.nameRes)
        TileActionSheet(title = title, onDismiss = onDismiss, height = ActionSheetTileMetrics.sheetHeight(tiles = 4)) {
                if (convo == null || contact == null) {
                    PinRow()
                    return@TileActionSheet
                }
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
                        subtitle = stringResource(R.string.puts_the_unread_badge_back_so_you),
                    ) {
                        onDismiss()
                        chatViewModel.markAsUnread(contact.id)
                    }
                }
                PinRow()
                ActionSheetRow(
                    icon = if (isSilent) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                    title = if (isSilent) stringResource(R.string.unsilence) else stringResource(R.string.silence),
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
                    subtitle = stringResource(R.string.bot_sheet_delete_keeps_circle),
                    tint = LocalAppColors.current.danger,
                ) {
                    onDismiss()
                    onDeleteBot(contact)
                }
        }
    }
}

/** The red unread count on the circles above the chat list (iOS `ChatsTabUnreadBadge`) - hidden
 *  at 0. */
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
 * A one-line preview of a message body for the chat list (iOS ConversationRow.formatPreview), so
 * a row never leaks a reply, media, chess or call JSON envelope to the user.
 */
private fun messagePreviewText(message: MessageEntity?, context: android.content.Context): String? {
    val body = message?.plaintextBody ?: return null
    // Hidden placeholders (cross-device, undecryptable) never surface anywhere. The chat list's
    // latest-message query already skips them; this is a backstop for any other route.
    if (MessageEntity.isHiddenPlaceholder(body)) return ""
    // Unwrap a reply envelope first, so a reply's own text (or its attachment, below) is what's
    // previewed rather than the raw JSON.
    val unwrapped = MessageReply.parseOrNull(body)?.text ?: body
    // A message that is nothing but a link back into KaChat previews as what it OPENS rather than
    // as a raw link - matching the card the bubble itself draws for it.
    when (val link = KaChatLink.parse(unwrapped)) {
        is KaChatLinkRef.Profile -> return context.getString(R.string.shared_a_kachat_profile)
        is KaChatLinkRef.PaymentRequest -> return com.kachat.app.util.KaspaUnit.label(
            context.getString(R.string.pr_preview, link.request.amountText)
        )
        is KaChatLinkRef.KaPost -> return context.getString(R.string.shared_a_kapost)
        null -> {}
    }
    val trimmed = unwrapped.trim()
    // Never a link in the row - see NextcloudShareSniff.linkSafePreview.
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
        return com.kachat.app.util.NextcloudShareSniff.linkSafePreview(unwrapped)
    }
    // Ahead of the chess and call parses, which deserialize the WHOLE payload: for an inline photo
    // or voice message that is tens of kilobytes of base64. The envelope types exclude each other,
    // so the order changes nothing else.
    fileEnvelopeMimeType(trimmed)?.lowercase()?.let { mime ->
        return when {
            mime.startsWith("image/") -> context.getString(R.string.photo)
            mime.startsWith("audio/") -> context.getString(R.string.voice_message)
            mime.startsWith("video/") -> context.getString(R.string.video)
            else -> context.getString(R.string.composer_file)
        }
    }
    val chess = com.kachat.app.util.ChessMessage.parseOrNull(unwrapped)
    if (chess != null) {
        val invite = (chess as? com.kachat.app.util.ChessEnvelope.Invite)?.content
        return if (invite?.tcMinutes != null) {
            context.getString(R.string.chess_game, invite.tcMinutes, invite.tcIncSeconds ?: 0)
        } else {
            context.getString(R.string.chess_game_2)
        }
    }
    return com.kachat.app.util.NextcloudShareSniff.linkSafePreview(unwrapped)
}

/**
 * The `mimeType` of a `{"type":"file",...}` envelope, else null. Walks the JSON tokens and stops
 * once both keys are seen, so the `content` data URL is at most skipped over, never built.
 */
private fun fileEnvelopeMimeType(json: String): String? = try {
    com.google.gson.stream.JsonReader(java.io.StringReader(json)).use { reader ->
        var type: String? = null
        var mimeType: String? = null
        reader.beginObject()
        while (reader.hasNext() && (type == null || mimeType == null)) {
            val name = reader.nextName()
            if ((name == "type" || name == "mimeType") && reader.peek() == com.google.gson.stream.JsonToken.STRING) {
                val value = reader.nextString()
                if (name == "type") type = value else mimeType = value
            } else {
                reader.skipValue()
            }
        }
        if (type == "file") mimeType else null
    }
} catch (e: Exception) {
    null
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    convo: Conversation,
    latestReaction: com.kachat.app.services.database.LatestReactionRow?,
    myAddress: String?,
    fetchState: com.kachat.app.repository.ChatRepository.ChatFetchState? = null,
    onLongClick: (() -> Unit)? = null,
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
                        contentDescription = stringResource(R.string.silenced),
                        tint = LocalAppColors.current.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
              }
                // This chat's own fetch (iOS chatFetchStates): a mini spinner while there is
                // something to parse or the chat was just opened, an orange triangle if it failed.
                when (fetchState) {
                    com.kachat.app.repository.ChatRepository.ChatFetchState.LOADING -> {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            color = LocalAppColors.current.textSecondary,
                            strokeWidth = 1.5.dp,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    com.kachat.app.repository.ChatRepository.ChatFetchState.FAILED -> {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = LocalAppColors.current.warning,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    null -> {}
                }
                // When the last message came: the time today, "Yesterday", else the day (iOS
                // formatDate - caption, secondary).
                convo.lastMessage?.let { last ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = chatListDate(last.blockTimestamp, stringResource(R.string.chat_day_yesterday)),
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
                    reactedByMe && targetIsMine -> stringResource(R.string.you_reacted_to_your_message)
                    reactedByMe -> stringResource(R.string.you_reacted_to_their_message)
                    targetIsMine -> stringResource(R.string.reacted_to_your_message)
                    else -> stringResource(R.string.reacted_to_their_message)
                }
            }
            // Computed once per message, not once per recomposition. The chat list re-renders on
            // every sync tick, and this walks the message body looking for a reply envelope and a
            // media mime - cheap now, but not free, and there are as many of these as there are
            // visible rows.
            val previewContext = LocalContext.current
            val lastBody = convo.lastMessage?.plaintextBody
            // The body's length and head, as iOS's preview cache: it can change under one id (an
            // edit, or a message that could not be decrypted until now).
            val preview = remember(convo.lastMessage?.id, lastBody?.length, lastBody?.take(24)) {
                messagePreviewText(convo.lastMessage, previewContext)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The row said nothing about whether your own last message went out. iOS's list
                // has carried this since bb1f9f5; Android's never had it at all. Compact
                // rendering: a bare check in the secondary colour, not the bubble's green circle.
                val lastMessage = convo.lastMessage
                if (lastMessage != null && (lastMessage.direction == "sent" || lastMessage.deliveryStatus == "warning")) {
                    DeliveryStatusLabel(
                        status = deliveryStatusOf(lastMessage.deliveryStatus, isOutgoing = lastMessage.direction == "sent"),
                        compact = true,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = reactionPreview ?: preview ?: stringResource(R.string.no_messages_yet),
                    // iOS .subheadline: 15pt, and italic with nothing to preview yet.
                    fontSize = 15.sp,
                    fontStyle = if (reactionPreview == null && preview == null) {
                        androidx.compose.ui.text.font.FontStyle.Italic
                    } else {
                        androidx.compose.ui.text.font.FontStyle.Normal
                    },
                    color = LocalAppColors.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The rest of the row, so the badge sits at its end without halving the text.
                    modifier = Modifier.weight(1f),
                )
                if (convo.unreadCount > 0) {
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
internal fun chatListDate(timestampMs: Long, yesterdayLabel: String): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(then.time)
    val yesterday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    if (yesterday.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        yesterday.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    ) return yesterdayLabel
    return java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(then.time)
}

/**
 * Contact avatar — the single place the app's avatar resolution order lives, used everywhere a
 * contact is shown:
 *
 *   1. The photo you assigned to [address] in Contacts. Your own choice, so it wins over the
 *      avatar the address set for itself, as it does on the Contacts screen (iOS d1844582).
 *   2. [imageUrl] — the contact's KNS profile photo (a remote https URL), when they have one.
 *   3. [backupPhotoBase64] — the photo carried in the chat backup (e.g. one set on desktop).
 *   4. the person glyph.
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
    /** Whose avatar this is (a `kaspa:` / `kaspatest:` address): your Contacts photo for it, and the
     *  testnet `.kachat` rule. */
    address: String? = null,
    /** False leaves your Contacts photo out: [AddressBookAvatar] works it out itself, the editor's
     *  not-yet-saved choice included (iOS `includeBackupPhoto: false`). */
    includeAssignedPhoto: Boolean = true,
) {
    val book = if (includeAssignedPhoto) com.kachat.app.services.AddressBookManager.shared else null
    val photoVersion = book?.photoVersion ?: 0
    val assignedPhoto = remember(address, photoVersion, book) { book?.photo(address)?.asImageBitmap() }
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
        if (assignedPhoto != null) {
            Image(
                bitmap = assignedPhoto,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            AvatarImageChain(candidates, fallbackText, fontSize, backupBitmap)
        }
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
            imageLoader = com.kachat.app.services.LinkPreviewService.profileImageLoader(androidx.compose.ui.platform.LocalContext.current),
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
    val requests by chatViewModel.messageRequests.collectAsState()
    val groupInvites by chatViewModel.pendingGroupInvites.collectAsState()
    val groupMemberNames by chatViewModel.groupMemberNamesByAddress.collectAsState()
    val latestReactionByContact by chatViewModel.latestReactionByContact.collectAsState()
    val chatFetchStates by chatViewModel.chatFetchStates.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    // A sheet on iOS, not a pushed page.
    IosSheetPage(grouped = false) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                title = { Text(stringResource(R.string.message_requests), color = colors.textPrimary, fontWeight = FontWeight.SemiBold) },
                actions = {
                    SheetBarButton(stringResource(R.string.done), onClick = { navController.popBackStack() }, modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        if (requests.isEmpty() && groupInvites.isEmpty()) {
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
                if (groupInvites.isNotEmpty()) {
                    item {
                        GroupInvitesSection(groupInvites, groupMemberNames) { invite ->
                            navController.navigate("group_invite/${android.net.Uri.encode(invite.id)}")
                        }
                    }
                }
                if (requests.isNotEmpty()) item {
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
                            ConversationRow(convo, latestReactionByContact[convo.contact.id], myAddress, chatFetchStates[convo.contact.id]) {
                                navController.navigate("chat/${convo.contact.id}")
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** What the Chats New sheet shows: its menu, or a create screen swapped in (iOS d1844582
 *  `CreateStep`). */
private enum class ChatsNewPage { MENU, NEW_CHAT, NEW_GROUP }

/**
 * The Chats screen's New sheet (iOS d1844582 `createSheet`): a half sheet of two rows, New Chat and
 * New Group Chat. Each swaps the sheet to the create screen, full height ([CreateChatScreen], whose
 * Cancel comes back to the menu).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsNewSheet(
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onChatCreated: (String) -> Unit,
    onGroupCreated: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(ChatsNewPage.MENU) }

    /** Closes the sheet and runs [then] once it has gone (iOS `closeCreateSheet(then:)`). */
    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }

    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        // On the menu the sheet rests on iOS's 250 detent, where iOS 26 floats it; New Chat / New
        // Group switch to .large, which runs edge to edge.
        val floating = page == ChatsNewPage.MENU
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            modifier = if (floating) Modifier.iosFloatingSheet() else Modifier,
            shape = if (floating) IosFloatingSheetShape else com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = colors.background,
            // iOS's grabber is drawn over the sheet, not in a row above it: the menu is iOS's 250
            // tall in all (createMenuHeight), with "New" 20 below the top edge.
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
                    ChatsNewPage.MENU -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ChatsNewSheetHeight)
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.chats_new),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                        )
                        ActionSheetRow(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = stringResource(R.string.chats_new_chat),
                            subtitle = stringResource(R.string.chats_new_chat_subtitle),
                        ) { page = ChatsNewPage.NEW_CHAT }
                        ActionSheetRow(
                            icon = Icons.Outlined.Groups,
                            title = stringResource(R.string.chats_new_group_chat),
                            subtitle = stringResource(R.string.chats_new_group_subtitle),
                        ) { page = ChatsNewPage.NEW_GROUP }
                    }
                }
                IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/**
 * Wallet's Send (iOS bfe9f022): Send Kaspa ([SpendingAddressSendFlow]) in a sheet of its own, which
 * only Cancel closes. It starts from the chatting address, and From picks any other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatsSpendingSendLauncher(walletViewModel: WalletViewModel, onDone: () -> Unit) {
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
        )
    }
}

/** New Chat and New Group Chat open at full height (iOS d1844582 `createMenuHeight`, 250 pt). */
private val ChatsNewSheetHeight = 250.dp
