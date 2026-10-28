package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.models.liveNameFor
import com.kachat.app.repository.GroupConversation
import com.kachat.app.repository.GroupMessage
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.MessageReply
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.launch

/**
 * Group Chats, a dock tab of its own (iOS a53d343 `GroupChatListView`; from 2026-10-05 they were
 * circles in the row above the chats). Pinned groups live in a row of circles above the list (Pin
 * to Top Row, iOS 7abc06e); the list holds the rest by latest activity. Tap opens; hold opens the
 * half sheet (read state, pin, notifications, delete); Select marks read, unread or deletes
 * several at once. The + starts a new group. A tapped group notification lands here (MainShell
 * switches to this tab) and opens its group.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun GroupChatsScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val groupConversations by chatViewModel.groupConversations.collectAsState()
    val isRefreshing by chatViewModel.isRefreshingGroups.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = com.kachat.app.util.rememberHaptics()
    val dockInset = LocalDockInset.current

    var searchQuery by remember { mutableStateOf("") }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCreateGroup by remember { mutableStateOf(false) }
    /** The row whose long-press half sheet is up. */
    var actionTarget by remember { mutableStateOf<String?>(null) }
    /** A group waiting on its delete confirmation, from that sheet. */
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showBulkDeleteConfirmation by remember { mutableStateOf(false) }

    // Group ids pinned up into the row above the list, newest pin first, saved per wallet.
    val pins by remember(myAddress) {
        myAddress?.let { chatViewModel.groupPins(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // An account switch keeps this screen alive: every account starts on its own list.
    LaunchedEffect(myAddress) {
        isSelectionMode = false
        selectedGroupIds = emptySet()
    }

    // Filtered by search on the group name, each member's name or address, and message content;
    // until the search answers, the previous answer stays.
    val searchedGroupIds by produceState<Set<String>?>(initialValue = null, groupConversations, searchQuery) {
        value = if (searchQuery.isBlank()) null
        else chatViewModel.groupIdsMatchingSearch(groupConversations.map { it.group }, searchQuery)
    }
    // The groups in the row above the list, in pin order (newest pin first). Empty while
    // searching: the results list them with the rest.
    val pinnedGroups = remember(groupConversations, pins, searchQuery) {
        if (searchQuery.isNotBlank()) emptyList()
        else {
            val byId = groupConversations.associateBy { it.group.groupId }
            pins.mapNotNull { byId[it] }
        }
    }
    // The list under the pinned row: the unpinned groups by latest activity - the last message,
    // else when the group was created, so a brand-new empty group still sorts by when it was
    // added. While searching, every matching group, pinned or not. Shared by Select All and the
    // bulk actions so they agree on what's visible.
    val displayedGroups = remember(groupConversations, pins, searchedGroupIds, searchQuery) {
        val sorted = groupConversations.sortedByDescending { it.lastMessage?.blockTimestamp ?: it.group.createdAt }
        val matches = searchedGroupIds
        when {
            searchQuery.isBlank() -> sorted.filter { it.group.groupId !in pins }
            matches == null -> emptyList()
            else -> sorted.filter { it.group.groupId in matches }
        }
    }
    val isEverythingSelected = displayedGroups.isNotEmpty() && selectedGroupIds.size >= displayedGroups.size

    fun endSelection() {
        isSelectionMode = false
        selectedGroupIds = emptySet()
    }

    fun deleteGroups(ids: Set<String>) {
        if (ids.isEmpty()) return
        chatViewModel.deleteGroupChats(ids)
        myAddress?.let { address -> scope.launch { chatViewModel.unpinGroups(address, ids) } }
        selectedGroupIds = emptySet()
        IosToasts.show(if (ids.size == 1) "Group deleted." else "${ids.size} groups deleted.")
    }

    /** Hold a group: pin it up into the row above the list (newest pin first), or unpin it. */
    fun togglePin(groupId: String) {
        val address = myAddress ?: return
        scope.launch {
            val pinned = chatViewModel.toggleGroupPin(address, groupId)
            IosToasts.show(context.getString(if (pinned) R.string.pinned_to_top_row else R.string.chats_unpinned))
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { chatViewModel.refreshGroups() },
    )

    Scaffold(
        containerColor = LocalAppColors.current.background,
        // The list runs on under the dock (and the system bar under it); it pads its own end.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(
                modifier = Modifier
                    .background(LocalAppColors.current.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                // Chats' header: the status/balance bar, the large title, the search field.
                TopStatusBar(
                    balance = balance,
                    onStatusClick = { ConnectionStatusOverlayState.open() },
                    dotColorHex = dotColorHex,
                    showAddButton = false,
                    showEditButton = groupConversations.isNotEmpty(),
                    isEditing = isSelectionMode,
                    onEditClick = { if (isSelectionMode) endSelection() else isSelectionMode = true },
                    selectAllLabel = if (isEverythingSelected) "Deselect All" else "Select All",
                    onSelectAllClick = {
                        selectedGroupIds = if (isEverythingSelected) emptySet() else displayedGroups.map { it.group.groupId }.toSet()
                    },
                )
                Text(
                    stringResource(R.string.groups_tab),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = LargeTitleSize,
                    modifier = Modifier.padding(top = 9.dp, bottom = 8.dp)
                )
                IosSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.groups_search),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        },
        floatingActionButton = {
            // The floating glass + (as on Chats): straight to New Group Chat. Not while selecting.
            if (isSelectionMode) return@Scaffold
            com.kachat.app.ui.theme.IosGlassFab(
                // Above the dock the list runs under.
                modifier = Modifier.padding(bottom = dockInset),
                onClick = {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    showCreateGroup = true
                },
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.chats_new_group_chat),
            )
        },
        bottomBar = {
            // Mark read, mark unread and delete, for every selected group.
            if (isSelectionMode) {
                Column(modifier = Modifier.background(LocalAppColors.current.background).navigationBarsPadding()) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                chatViewModel.markGroupsAsRead(selectedGroupIds)
                                endSelection()
                            },
                            enabled = selectedGroupIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailRead, stringResource(R.string.read), tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                        Button(
                            onClick = {
                                chatViewModel.markGroupsAsUnread(selectedGroupIds)
                                endSelection()
                            },
                            enabled = selectedGroupIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailUnread, stringResource(R.string.unread), tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                        Button(
                            onClick = { showBulkDeleteConfirmation = true },
                            enabled = selectedGroupIds.isNotEmpty(),
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // The last row scrolls clear of the dock it runs under (none while selecting:
                // the selection bar replaces the dock and pads itself).
                contentPadding = PaddingValues(bottom = if (isSelectionMode) 0.dp else dockInset),
            ) {
                // The pinned groups, as circles above the list - swipe sideways for all of them.
                // Tap opens; hold opens the group's half sheet (with Unpin).
                if (pinnedGroups.isNotEmpty()) {
                    item(key = "pinned_groups") {
                        PinnedCirclesRow {
                            pinnedGroups.forEach { convo ->
                                PinnedCircle(
                                    name = convo.group.name,
                                    unreadCount = convo.unreadCount,
                                    showsPin = false,
                                    isSelecting = isSelectionMode,
                                    onTap = { navController.navigate("group_chat/${convo.group.groupId}") },
                                    onLongPress = { actionTarget = convo.group.groupId },
                                ) { GroupListAvatar(photoHex = convo.group.photoHex, size = 60.dp) }
                            }
                        }
                    }
                }
                items(displayedGroups, key = { it.group.groupId }) { convo ->
                    val groupId = convo.group.groupId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().background(LocalAppColors.current.background)
                    ) {
                        if (isSelectionMode) {
                            Icon(
                                imageVector = if (groupId in selectedGroupIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = stringResource(R.string.select_group),
                                tint = if (groupId in selectedGroupIds) KaspaTeal else LocalAppColors.current.textSecondary,
                                modifier = Modifier.padding(start = 16.dp).size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            GroupChatRow(
                                convo = convo,
                                chatViewModel = chatViewModel,
                                myAddress = myAddress,
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        haptic(com.kachat.app.util.IosHaptic.IMPACT_MEDIUM)
                                        actionTarget = groupId
                                    }
                                },
                            ) {
                                if (isSelectionMode) {
                                    selectedGroupIds = if (groupId in selectedGroupIds) selectedGroupIds - groupId else selectedGroupIds + groupId
                                } else {
                                    navController.navigate("group_chat/$groupId")
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
            }

            if (displayedGroups.isEmpty() && pinnedGroups.isEmpty() && searchQuery.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Groups,
                        contentDescription = null,
                        tint = LocalAppColors.current.textSecondary,
                        modifier = Modifier.size(60.dp),
                    )
                    Text(
                        stringResource(R.string.no_group_chats_yet),
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.groups_empty_body),
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = LocalAppColors.current.surface,
                contentColor = KaspaTeal
            )
        }
    }

    actionTarget?.let { groupId ->
        GroupChatActionSheet(
            groupId = groupId,
            isPinned = groupId in pins,
            groupConversations = groupConversations,
            chatViewModel = chatViewModel,
            onDismiss = { actionTarget = null },
            onTogglePin = { togglePin(groupId) },
            onDelete = { deleteTarget = groupId },
        )
    }

    deleteTarget?.let { groupId ->
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete Group?", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "This removes this group and its messages from this device. This cannot be undone, and other members won't be notified.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteGroups(setOf(groupId))
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showBulkDeleteConfirmation) {
        val count = selectedGroupIds.size
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showBulkDeleteConfirmation = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete $count Group${if (count == 1) "" else "s"}?", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "This removes each selected group and its messages from this device. This cannot be undone, and other members won't be notified.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteGroups(selectedGroupIds)
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

    // New Group Chat in a sheet of its own, as iOS presents AddContactView(startInGroupMode:).
    if (showCreateGroup) {
        IosFullSheet(onDismissed = { showCreateGroup = false }, swipeToDismiss = true) { close ->
            Box(Modifier.fillMaxHeight().padding(top = 10.dp)) {
                CreateChatScreen(
                    onBack = close,
                    onCancel = close,
                    onChatCreated = { address ->
                        close()
                        navController.navigate("chat/$address")
                    },
                    onGroupCreated = { groupId ->
                        close()
                        navController.navigate("group_chat/$groupId")
                    },
                    startInGroupMode = true,
                    chatViewModel = chatViewModel,
                )
            }
        }
    }
}

/** A group row's long-press half sheet (iOS a53d343 `actionSheet(for:)`): read state, pin,
 *  notifications and delete, as square tiles. */
@Composable
private fun GroupChatActionSheet(
    groupId: String,
    isPinned: Boolean,
    groupConversations: List<GroupConversation>,
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val silentGroups by chatViewModel.groupSilent.collectAsState()
    val convo = groupConversations.firstOrNull { it.group.groupId == groupId } ?: return
    val isSilent = groupId in silentGroups
    TileActionSheet(title = convo.group.name, onDismiss = onDismiss, height = ActionSheetTileMetrics.sheetHeight(tiles = 4)) {
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
        ActionSheetRow(
            icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
            title = stringResource(if (isPinned) R.string.chats_circle_unpin else R.string.pin_to_top_row),
            subtitle = stringResource(if (isPinned) R.string.groups_unpin_top_hint else R.string.groups_pin_top_hint),
        ) {
            onDismiss()
            onTogglePin()
        }
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
            // After the sheet: the alert can't present while it is going down.
            onDelete()
        }
    }
}

/**
 * One group in Group Chats (iOS `GroupChatRow`): photo, name, the latest message (or reaction)
 * and the unread count. Laid out as the chat rows ([ConversationRow]).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupChatRow(
    convo: GroupConversation,
    chatViewModel: ChatViewModel,
    myAddress: String?,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val silentGroups by chatViewModel.groupSilent.collectAsState()
    val latestReactionByGroup by chatViewModel.latestReactionByGroup.collectAsState()
    val memberNamesByAddress by chatViewModel.groupMemberNamesByAddress.collectAsState()
    val group = convo.group
    // Memoized on the roster JSON so scrolling doesn't re-parse the member list per row.
    val members = remember(group.membersJson) { parseGroupMembers(group) }
    // The app's one name rule: your name for them, else their .kachat name, else the short address.
    val resolveName: (String) -> String = { address ->
        memberNamesByAddress.liveNameFor(address)
            ?: members.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
            ?: com.kachat.app.util.KaspaAddress.shortDisplay(address)
    }
    // A reaction newer than the last message becomes the row's preview - reactions never become
    // messages (they render as a corner pill), so without this the row would keep showing an
    // older message as if nothing happened.
    val reactionPreview = latestReactionByGroup[group.groupId]?.let { reaction ->
        if (convo.lastMessage != null && convo.lastMessage.blockTimestamp >= reaction.blockTimestamp) return@let null
        val targetIsMine = reaction.targetIsOutgoing == true
        if (reaction.reactorAddress == myAddress) {
            if (targetIsMine) "You reacted to your message" else "You reacted to a message"
        } else {
            val name = resolveName(reaction.reactorAddress)
            if (targetIsMine) "$name reacted to your message" else "$name reacted to a message"
        }
    }
    val preview = remember(convo.lastMessage?.txId, members, memberNamesByAddress) {
        groupMessagePreviewText(convo.lastMessage, members, resolveName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupListAvatar(photoHex = group.photoHex, size = 50.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.name,
                        fontSize = 17.sp,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Silenced: no banner from this group, mentioned or not.
                    if (group.groupId in silentGroups) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Silenced",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                convo.lastMessage?.let { last ->
                    Spacer(Modifier.width(8.dp))
                    Text(chatListDate(last.blockTimestamp), fontSize = 12.sp, color = colors.textSecondary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val memberCount = members.size
                Text(
                    text = reactionPreview ?: preview ?: "$memberCount member${if (memberCount == 1) "" else "s"}",
                    fontSize = 15.sp,
                    fontStyle = if (reactionPreview == null && preview == null) FontStyle.Italic else FontStyle.Normal,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (convo.unreadCount > 0) {
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    // caption2 bold white on the accent, 6 by 2 padding, a capsule.
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 18.dp)
                            .background(KaspaTeal, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(convo.unreadCount.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** A group's photo in a circle, or the group symbol on the accent tint when it has none (iOS
 *  `GroupAvatar`). */
@Composable
private fun GroupListAvatar(photoHex: String?, size: Dp) {
    if (!photoHex.isNullOrEmpty()) {
        GroupAvatar(photoHex = photoHex, size = size)
    } else {
        Box(
            modifier = Modifier.size(size).background(KaspaTeal.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(size * 0.46f))
        }
    }
}

/**
 * A one-line preview of a group message (iOS `GroupChatRow`: the reply text, never a link,
 * mentions as names). Media shows as what it is, as the chat rows do.
 */
private fun groupMessagePreviewText(
    message: GroupMessage?,
    members: List<com.kachat.app.models.GroupMember>,
    resolve: (String) -> String,
): String? {
    // A reply previews as its own text (iOS MessageReplyCodec.previewText unwraps it).
    val body = message?.content?.let { MessageReply.parseOrNull(it)?.text ?: it } ?: return null
    com.kachat.app.util.InlineMediaSniff.mimeType(body)?.let { mime ->
        return when {
            mime.startsWith("audio/") -> "🎤 Audio message"
            mime.startsWith("image/") -> "📷 Photo"
            mime.startsWith("video/") -> "🎬 Video"
            else -> "📎 File"
        }
    }
    return GroupMentionCodec.decodeForDisplay(com.kachat.app.util.NextcloudShareSniff.linkSafePreview(body), members, resolve)
}
