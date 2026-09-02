package com.kachat.app.ui.screens

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import com.kachat.app.ui.theme.KaspaBlue
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.models.avatarFallbackText
import com.kachat.app.models.displayName
import com.kachat.app.models.Conversation
import com.kachat.app.models.GroupMember
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PersonAddAlt1
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
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import com.kachat.app.repository.GroupConversation
import com.kachat.app.repository.GroupMessage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Lets something outside this screen ask it to open on a particular tab. Set by MainShell when a
 * group notification names no local group to open (see NotificationHelper.EXTRA_OPEN_GROUPS):
 * landing on the 1:1 Chats list would say nothing about the group the ping was for.
 */
object ChatsTabIntake {
    val pendingGroupsTab = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * Chats tab — conversation list.
 * Phase 4 will wire this up to ChatService / ChatViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    navController: NavController, 
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState()
    val groupConversations by chatViewModel.groupConversations.collectAsState()
    val latestReactionByContact by chatViewModel.latestReactionByContact.collectAsState()
    val latestReactionByGroup by chatViewModel.latestReactionByGroup.collectAsState()
    // address -> alias/KNS display name, same map the group thread's sender labels use - lets the
    // group cards name people the full alias > KNS > roster > short-address way instead of
    // falling straight from roster snapshot to raw address.
    val groupMemberNamesByAddress by chatViewModel.groupMemberNamesByAddress.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    val isRefreshing by chatViewModel.isRefreshing.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkDeleteConfirmation by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    // Selection is scoped to whichever tab it was started on - switching tabs mid-select would
    // either strand a selection the visible list can't act on, or blend Chats and Group Chats
    // selections together, so the other tab is blocked while editing (matches iOS).
    val isOnGroupsTab = pagerState.currentPage == 1
    val tabCoroutineScope = rememberCoroutineScope()

    // A group notification with no openable thread asked for the Group Chats tab — see
    // [ChatsTabIntake]. Consumed once, so a later manual swipe back to Chats sticks.
    val pendingGroupsTab by ChatsTabIntake.pendingGroupsTab.collectAsState()
    LaunchedEffect(pendingGroupsTab) {
        if (pendingGroupsTab) {
            ChatsTabIntake.pendingGroupsTab.value = false
            runCatching { pagerState.animateScrollToPage(1) }
        }
    }

    // Matches on whatever's already shown per row — display name/alias, KNS domain, the raw
    // address (so pasting/typing part of an address you recognize still finds it), and the last
    // message preview text (reply/voice-aware, same as what's rendered) — not just the name.
    val filteredConversations = remember(conversations, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter { convo ->
                val contactLabel = convo.contact.displayName
                listOfNotNull(
                    convo.contact.alias,
                    convo.contact.knsName,
                    convo.contact.id,
                    messagePreviewText(convo.lastMessage, contactLabel)
                ).any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    // Mirrors filteredConversations above for the Group Chats tab: group name, each member's
    // display-name-or-address, and the last message preview text.
    val filteredGroupConversations = remember(groupConversations, searchQuery, groupMemberNamesByAddress) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            groupConversations
        } else {
            groupConversations.filter { convo ->
                val members = parseGroupMembers(convo.group)
                listOfNotNull(convo.group.name, groupMessagePreviewText(convo.lastMessage, members, groupMemberNamesByAddress))
                    .any { it.contains(query, ignoreCase = true) } ||
                    members.any { member ->
                        (member.displayName?.contains(query, ignoreCase = true) == true) ||
                            member.address.contains(query, ignoreCase = true)
                    }
            }
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
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
    }

    // Auto-link/autocreate system contacts, same trigger point — matches iOS's
    // SystemContactsService refresh running on every app foreground.
    LaunchedEffect(Unit) {
        chatViewModel.syncSystemContacts()
    }

    val context = LocalContext.current
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

    Scaffold(
        containerColor = LocalAppColors.current.background,
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
                        showEditButton = if (isOnGroupsTab) groupConversations.isNotEmpty() else conversations.isNotEmpty(),
                        isEditing = isSelectionMode,
                        onEditClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) {
                                selectedContactIds = emptySet()
                                selectedGroupIds = emptySet()
                            }
                        },
                        selectAllLabel = if (isOnGroupsTab) {
                            if (selectedGroupIds.size == filteredGroupConversations.size && filteredGroupConversations.isNotEmpty()) "Deselect All" else "Select All"
                        } else {
                            if (selectedContactIds.size == filteredConversations.size && filteredConversations.isNotEmpty()) "Deselect All" else "Select All"
                        },
                        onSelectAllClick = {
                            if (isOnGroupsTab) {
                                selectedGroupIds = if (selectedGroupIds.size == filteredGroupConversations.size) {
                                    emptySet()
                                } else {
                                    filteredGroupConversations.map { it.group.groupId }.toSet()
                                }
                            } else {
                                selectedContactIds = if (selectedContactIds.size == filteredConversations.size) {
                                    emptySet()
                                } else {
                                    filteredConversations.map { it.contact.id }.toSet()
                                }
                            }
                        }
                    )

                    Text(
                        stringResource(R.string.chats),
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )

                    // Search Bar
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(22.dp)),
                        placeholder = { Text(stringResource(R.string.search_chats), color = LocalAppColors.current.textSecondary) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search), tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = LocalAppColors.current.surface,
                            unfocusedContainerColor = LocalAppColors.current.surface,
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            cursorColor = KaspaTeal,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
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

                val chatsUnreadCount = conversations.sumOf { it.unreadCount }
                val groupsUnreadCount = groupConversations.sumOf { it.unreadCount }
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = LocalAppColors.current.background,
                    contentColor = KaspaTeal
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            if (!isSelectionMode) tabCoroutineScope.launch { pagerState.animateScrollToPage(0) }
                        },
                        text = {
                            TabBadge(count = chatsUnreadCount) {
                                Text(
                                    stringResource(R.string.chats),
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelectionMode && isOnGroupsTab) LocalContentColor.current.copy(alpha = 0.25f) else LocalContentColor.current
                                )
                            }
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            if (!isSelectionMode) tabCoroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        text = {
                            TabBadge(count = groupsUnreadCount) {
                                Text(
                                    stringResource(R.string.group_chats),
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelectionMode && !isOnGroupsTab) LocalContentColor.current.copy(alpha = 0.25f) else LocalContentColor.current
                                )
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            // Same style/placement as Portfolio's add-transaction FAB (see PortfolioScreen.kt) —
            // sits above the app-wide floating tab bar for free, since this screen's own content
            // region is already reserved above it before this Scaffold is even composed.
            FloatingActionButton(
                // Tab-aware: opens the group builder on the Group Chats tab, the 1:1 create
                // screen on the Chats tab.
                onClick = { navController.navigate(if (isOnGroupsTab) "create_chat?group=true" else "create_chat") },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.PersonAddAlt1, "Create chat", modifier = Modifier.size(28.dp))
            }
        },
        bottomBar = {
            if (isSelectionMode) {
                Column(modifier = Modifier.background(LocalAppColors.current.background).navigationBarsPadding()) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isOnGroupsTab) {
                                    chatViewModel.markGroupsAsRead(selectedGroupIds)
                                } else {
                                    chatViewModel.markContactsAsRead(selectedContactIds)
                                }
                                isSelectionMode = false
                                selectedContactIds = emptySet()
                                selectedGroupIds = emptySet()
                            },
                            enabled = if (isOnGroupsTab) selectedGroupIds.isNotEmpty() else selectedContactIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailRead, stringResource(R.string.read), tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                        Button(
                            onClick = {
                                if (isOnGroupsTab) {
                                    chatViewModel.markGroupsAsUnread(selectedGroupIds)
                                } else {
                                    chatViewModel.markContactsAsUnread(selectedContactIds)
                                }
                                isSelectionMode = false
                                selectedContactIds = emptySet()
                                selectedGroupIds = emptySet()
                            },
                            enabled = if (isOnGroupsTab) selectedGroupIds.isNotEmpty() else selectedContactIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailUnread, stringResource(R.string.unread), tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                        Button(
                            onClick = { showBulkDeleteConfirmation = true },
                            enabled = if (isOnGroupsTab) selectedGroupIds.isNotEmpty() else selectedContactIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        // Tap-only (userScrollEnabled = false), not swipeable - a draggable pager here would
        // fight the row-level swipe-to-delete/mark-read gestures on both the Chats and Group
        // Chats lists. Tab taps still drive it via pagerState.animateScrollToPage above.
        HorizontalPager(
            state = pagerState,
            // Allow swiping left/right between the Chats and Group Chats tabs (the tab row
            // still works too - both drive the same pagerState).
            userScrollEnabled = true,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
        when (page) {
            1 -> GroupListBody(
                navController = navController,
                groupConversations = filteredGroupConversations,
                hasAnyGroups = groupConversations.isNotEmpty(),
                searchQuery = searchQuery,
                latestReactionByGroup = latestReactionByGroup,
                memberNamesByAddress = groupMemberNamesByAddress,
                myAddress = myAddress,
                onDeleteGroup = { chatViewModel.deleteGroupChat(it) },
                isSelectionMode = isSelectionMode,
                selectedGroupIds = selectedGroupIds,
                onToggleGroupSelected = { groupId ->
                    selectedGroupIds = if (groupId in selectedGroupIds) {
                        selectedGroupIds - groupId
                    } else {
                        selectedGroupIds + groupId
                    }
                },
                onMarkGroupRead = { chatViewModel.markGroupsAsRead(listOf(it)) },
                onMarkGroupUnread = { chatViewModel.markGroupsAsUnread(listOf(it)) }
            )
            else -> Box(
            modifier = Modifier
                .fillMaxSize()
                .pullRefresh(pullRefreshState)
        ) {
            if (conversations.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 100.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_kachat_logo),
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            alpha = 0.5f // Dimmed logo like in screenshot
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.no_conversations_yet),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = LocalAppColors.current.textPrimary
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.start_a_new_chat_by_adding),
                            style = MaterialTheme.typography.bodyLarge,
                            color = LocalAppColors.current.textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { navController.navigate("create_chat") },
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp).padding(horizontal = 24.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PersonAddAlt1,
                                    contentDescription = null,
                                    tint = Color.Black
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.add_contact),
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else if (filteredConversations.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)
                ) {
                    Text(
                        text = stringResource(R.string.no_matching_chats),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = LocalAppColors.current.textPrimary
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No chats match \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalAppColors.current.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Swiping never deletes on its own — it only stages a confirmation below, since
                // unlike the old archive (reversible, one tap to undo) a delete permanently wipes
                // local message history and a mis-swipe would be unrecoverable.
                var contactToDelete by remember { mutableStateOf<String?>(null) }
                // Long-press quick menu target - which conversation's DropdownMenu is open.
                // Same Box-anchored DropdownMenu pattern as PortfolioPickerHeader's cards
                // (no onGloballyPositioned anchor math, which fillMaxWidth children corrupt).
                var menuContactId by remember { mutableStateOf<String?>(null) }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // 4.0: the Broadcasts entry card is gone - Broadcasts is a dock tab now,
                    // riding the Chats-slot cycle when the dock is full (matches iOS).
                    items(filteredConversations, key = { it.contact.id }) { convo ->
                        SwipeActionRow(
                            // 4.0 (matches iOS): row swipes are gone - horizontal swipes page
                            // between Chats and Groups; delete/read live in Select mode.
                            enabled = false,
                            leadingIcon = if (convo.unreadCount > 0) Icons.Default.MarkEmailRead else Icons.Default.MarkEmailUnread,
                            leadingLabel = if (convo.unreadCount > 0) "Read" else "Unread",
                            leadingColor = KaspaTeal,
                            onLeadingClick = {
                                if (convo.unreadCount > 0) {
                                    chatViewModel.markAsRead(convo.contact.id)
                                } else {
                                    chatViewModel.markAsUnread(convo.contact.id)
                                }
                            },
                            trailingIcon = Icons.Default.Delete,
                            trailingLabel = "Delete",
                            trailingColor = Color(0xFFFF3B30),
                            onTrailingClick = { contactToDelete = convo.contact.id }
                        ) {
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().background(LocalAppColors.current.background)
                                ) {
                                    if (isSelectionMode) {
                                        Icon(
                                            imageVector = if (convo.contact.id in selectedContactIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = stringResource(R.string.select_chat),
                                            tint = if (convo.contact.id in selectedContactIds) KaspaTeal else Color.Gray,
                                            modifier = Modifier.padding(start = 16.dp).size(22.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        ConversationRow(
                                            convo,
                                            latestReactionByContact[convo.contact.id],
                                            myAddress,
                                            onLongClick = { if (!isSelectionMode) menuContactId = convo.contact.id }
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
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 72.dp),
                                            color = Color.DarkGray.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = menuContactId == convo.contact.id,
                                    onDismissRequest = { menuContactId = null }
                                ) {
                                    if (convo.unreadCount > 0) {
                                        DropdownMenuItem(
                                            text = { Text("Mark as Read") },
                                            onClick = {
                                                menuContactId = null
                                                chatViewModel.markAsRead(convo.contact.id)
                                            }
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("Mark as Unread") },
                                            onClick = {
                                                menuContactId = null
                                                chatViewModel.markAsUnread(convo.contact.id)
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = Color(0xFFFF3B30)) },
                                        onClick = {
                                            menuContactId = null
                                            contactToDelete = convo.contact.id
                                        }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        val chatCount = conversations.size
                        Text(
                            text = "$chatCount ${if (chatCount == 1) "chat" else "chats"}",
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    }
                }

                contactToDelete?.let { contactId ->
                    val label = filteredConversations.find { it.contact.id == contactId }
                        ?.contact?.let { it.alias ?: com.kachat.app.util.KaspaAddress.shortDisplay(it.id) } ?: "this chat"
                    AlertDialog(
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
                                Text(stringResource(R.string.delete), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { contactToDelete = null }) {
                                Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                            }
                        }
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
        }

        // Screen-scoped, NOT inside a pager page: this used to compose inside the 1:1 Chats
        // page's non-empty branch, so on the Group Chats tab (page 0 not composed) tapping the
        // bulk Delete button set the flag but no dialog ever appeared - group bulk delete
        // silently did nothing. Same for an empty or fully filtered 1:1 list. Dialogs render
        // in their own window, so screen scope shows it regardless of which tab is visible.
        if (showBulkDeleteConfirmation) {
            val count = if (isOnGroupsTab) selectedGroupIds.size else selectedContactIds.size
            AlertDialog(
                onDismissRequest = { showBulkDeleteConfirmation = false },
                containerColor = LocalAppColors.current.surface,
                title = {
                    Text(
                        if (isOnGroupsTab) "Delete $count Group${if (count == 1) "" else "s"}?" else "Delete $count Chat${if (count == 1) "" else "s"}?",
                        color = LocalAppColors.current.textPrimary
                    )
                },
                text = {
                    Text(
                        if (isOnGroupsTab) {
                            "This removes each selected group and its messages from this device. This cannot be undone, and other members won't be notified."
                        } else {
                            "This permanently deletes every message in each selected chat, including from iCloud, so they're removed from your other devices too. This cannot be undone."
                        },
                        color = LocalAppColors.current.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (isOnGroupsTab) {
                            chatViewModel.deleteGroupChats(selectedGroupIds)
                        } else {
                            chatViewModel.deleteChats(selectedContactIds)
                        }
                        showBulkDeleteConfirmation = false
                        isSelectionMode = false
                        selectedContactIds = emptySet()
                        selectedGroupIds = emptySet()
                    }) {
                        Text(stringResource(R.string.delete), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
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
}

/** Small unread-count badge for the Chats/Group Chats tab labels - hidden entirely when count is 0.
 *  An inline pill next to the label (matching iOS's `chatsTabButton`) rather than `BadgedBox`'s
 *  corner-overlay style, which sat right on top of the label's last letter since Text has no
 *  built-in padding for a badge to offset into. */
@Composable
private fun TabBadge(count: Int, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
        if (count > 0) {
            Surface(color = Color(0xFFFF3B30), shape = RoundedCornerShape(50)) {
                Text(
                    if (count > 99) "99+" else count.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * Group Chats tab content embedded in `ChatsScreen`'s pager - list of joined groups with their
 * latest message, matching the 1:1 Chats page's row/footer/empty-state shape. Owns its own
 * delete-confirmation dialog - previously nested inside the 1:1 conversation list's `else`
 * branch, which meant it silently couldn't render whenever there were zero 1:1 chats; now
 * self-contained regardless of what the Chats page shows.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GroupListBody(
    navController: NavController,
    groupConversations: List<GroupConversation>,
    /** Whether the account has any groups at all, before search filtering - distinguishes a
     *  genuinely empty account from a search that just matched nothing. */
    hasAnyGroups: Boolean = groupConversations.isNotEmpty(),
    searchQuery: String = "",
    /** groupId -> newest reaction, for the "Alice reacted to a message" card preview - see
     *  [ChatViewModel.latestReactionByGroup]. */
    latestReactionByGroup: Map<String, com.kachat.app.services.database.LatestGroupReactionRow> = emptyMap(),
    /** address -> live alias/KNS display name ([ChatViewModel.groupMemberNamesByAddress]) - the
     *  same map the group thread's sender labels resolve through, so the cards name people
     *  identically: alias > KNS > roster snapshot > shortened address. */
    memberNamesByAddress: Map<String, String> = emptyMap(),
    myAddress: String? = null,
    onDeleteGroup: (String) -> Unit,
    isSelectionMode: Boolean = false,
    selectedGroupIds: Set<String> = emptySet(),
    onToggleGroupSelected: (String) -> Unit = {},
    onMarkGroupRead: (String) -> Unit = {},
    onMarkGroupUnread: (String) -> Unit = {}
) {
    var groupToDelete by remember { mutableStateOf<String?>(null) }
    // Long-press quick menu target - same Box-anchored DropdownMenu pattern as the 1:1 list.
    var menuGroupId by remember { mutableStateOf<String?>(null) }

    if (groupConversations.isEmpty() && hasAnyGroups && searchQuery.isNotBlank()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)
        ) {
            Text(
                text = stringResource(R.string.no_matching_groups),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = LocalAppColors.current.textPrimary
                )
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No groups match \"$searchQuery\"",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalAppColors.current.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    } else if (groupConversations.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Groups,
                contentDescription = null,
                tint = LocalAppColors.current.textSecondary,
                modifier = Modifier.size(60.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.no_group_chats_yet),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = LocalAppColors.current.textPrimary
                )
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.start_a_group_from_the_add),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalAppColors.current.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(groupConversations, key = { it.group.groupId }) { convo ->
                SwipeActionRow(
                    // 4.0 (matches iOS): row swipes are gone - see the 1:1 list above.
                    enabled = false,
                    leadingIcon = if (convo.unreadCount > 0) Icons.Default.MarkEmailRead else Icons.Default.MarkEmailUnread,
                    leadingLabel = if (convo.unreadCount > 0) "Read" else "Unread",
                    leadingColor = KaspaTeal,
                    onLeadingClick = {
                        if (convo.unreadCount > 0) {
                            onMarkGroupRead(convo.group.groupId)
                        } else {
                            onMarkGroupUnread(convo.group.groupId)
                        }
                    },
                    trailingIcon = Icons.Default.Delete,
                    trailingLabel = "Delete",
                    trailingColor = Color(0xFFFF3B30),
                    onTrailingClick = { groupToDelete = convo.group.groupId }
                ) {
                    // .background() is on this outer Column (covering the divider row below too),
                    // not just the inner Row - SwipeActionRow's teal/red swipe-action strips
                    // underneath are sized to this whole content block, and the divider's own
                    // `padding(start = 88.dp)` leaves a gap it doesn't paint over on the left edge
                    // (and only partially covers on the right, being semi-transparent) - without an
                    // opaque background spanning the full block, those gaps showed the swipe colors
                    // through as a stray line at the bottom of every row. Matches the regular Chats
                    // tab's identical row, which already scopes its background this way.
                    Column(modifier = Modifier.background(LocalAppColors.current.background)) {
                        Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            onToggleGroupSelected(convo.group.groupId)
                                        } else {
                                            navController.navigate("group_chat/${convo.group.groupId}")
                                        }
                                    },
                                    onLongClick = { if (!isSelectionMode) menuGroupId = convo.group.groupId }
                                )
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelectionMode) {
                                Icon(
                                    imageVector = if (convo.group.groupId in selectedGroupIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = stringResource(R.string.select_group),
                                    tint = if (convo.group.groupId in selectedGroupIds) KaspaTeal else Color.Gray,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            GroupAvatar(photoHex = convo.group.photoHex, size = 48.dp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = convo.group.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LocalAppColors.current.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                // Memoized on the roster JSON so scrolling / unread-count changes
                                // don't re-parse the whole member list (with a fresh Gson) per row.
                                val groupMembers = remember(convo.group.membersJson) { parseGroupMembers(convo.group) }
                                // A reaction more recent than the last message gets shown instead
                                // - mirrors the 1:1 list's reaction preview (see ConversationRow),
                                // since reactions never become message rows.
                                val reactionPreview = latestReactionByGroup[convo.group.groupId]?.let { reaction ->
                                    if (convo.lastMessage != null && convo.lastMessage.blockTimestamp >= reaction.blockTimestamp) {
                                        return@let null
                                    }
                                    // Same chain as the group thread's sender labels: live
                                    // alias/KNS name > roster snapshot > shortened address -
                                    // never the raw address.
                                    val reactorLabel = if (reaction.reactorAddress == myAddress) {
                                        "You"
                                    } else {
                                        memberNamesByAddress[reaction.reactorAddress]?.takeIf { it.isNotBlank() }
                                            ?: groupMembers.firstOrNull { it.address == reaction.reactorAddress }
                                                ?.displayName?.takeIf { it.isNotBlank() }
                                            ?: com.kachat.app.util.KaspaAddress.shortDisplay(reaction.reactorAddress)
                                    }
                                    val target = if (reaction.reactorAddress != myAddress && reaction.targetIsOutgoing == true) {
                                        "your message"
                                    } else {
                                        "a message"
                                    }
                                    "$reactorLabel reacted to $target"
                                }
                                Text(
                                    text = reactionPreview
                                        ?: groupMessagePreviewText(convo.lastMessage, groupMembers, memberNamesByAddress)
                                        ?: "No messages yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (convo.unreadCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                                        .background(KaspaTeal, CircleShape)
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = convo.unreadCount.toString(),
                                        color = LocalAppColors.current.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = menuGroupId == convo.group.groupId,
                            onDismissRequest = { menuGroupId = null }
                        ) {
                            if (convo.unreadCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("Mark as Read") },
                                    onClick = {
                                        menuGroupId = null
                                        onMarkGroupRead(convo.group.groupId)
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Mark as Unread") },
                                    onClick = {
                                        menuGroupId = null
                                        onMarkGroupUnread(convo.group.groupId)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete", color = Color(0xFFFF3B30)) },
                                onClick = {
                                    menuGroupId = null
                                    groupToDelete = convo.group.groupId
                                }
                            )
                        }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 88.dp),
                            color = Color.DarkGray.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            item {
                val groupCount = groupConversations.size
                Text(
                    text = "$groupCount ${if (groupCount == 1) "group" else "groups"}",
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                )
            }
        }
    }

    groupToDelete?.let { groupId ->
        val groupName = groupConversations.firstOrNull { it.group.groupId == groupId }?.group?.name ?: "this group"
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete \"$groupName\"", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.this_removes_the_group_and_its),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGroup(groupId)
                    groupToDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/** Mirrors [messagePreviewText] for group messages. Resolves `@{address}` mentions back to a
 *  display name through the same chain the group thread uses: live alias/KNS name (from
 *  [ChatViewModel.groupMemberNamesByAddress], passed in as [namesByAddress]) > the roster's
 *  `displayName` snapshot > shortened address. */
private fun groupMessagePreviewText(
    message: GroupMessage?,
    members: List<GroupMember> = emptyList(),
    namesByAddress: Map<String, String> = emptyMap()
): String? {
    val body = message?.content ?: return null
    val resolve: (String) -> String = { address ->
        namesByAddress[address]?.takeIf { it.isNotBlank() }
            ?: members.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
            ?: com.kachat.app.util.KaspaAddress.shortDisplay(address)
    }
    val replyContent = MessageReply.parseOrNull(body)
    if (replyContent != null) {
        return "Replied to \"${GroupMentionCodec.decodeForDisplay(replyContent.replyToPreview, members, resolve)}\""
    }
    if (VoiceMessage.parseOrNull(body) != null) return "🎤 Audio message"
    if (ImageMessage.parseOrNull(body) != null) return "📷 Photo"
    VoiceMessage.parseAnyFileOrNull(body)?.let {
        return if (it.mimeType.startsWith("video/")) "🎬 Video" else "📎 File"
    }
    return GroupMentionCodec.decodeForDisplay(body, members, resolve)
}

/**
 * A one-line preview of a message body, for the chat list and anywhere else a raw body would
 * otherwise leak the audio-message or reply JSON blob to the user. [contactLabel] names the other
 * party, used when they're the one who sent a reply ("Alice replied to ..." vs "You replied to ...").
 */
private fun messagePreviewText(message: MessageEntity?, contactLabel: String): String? {
    val body = message?.plaintextBody ?: return null
    val replyContent = MessageReply.parseOrNull(body)
    if (replyContent != null) {
        val who = if (message.direction == "sent") "You" else contactLabel
        return "$who replied to \"${replyContent.replyToPreview}\""
    }
    if (VoiceMessage.parseOrNull(body) != null) return "🎤 Audio message"
    if (ImageMessage.parseOrNull(body) != null) return "📷 Photo"
    if (com.kachat.app.util.ChessMessage.parseOrNull(body) != null) return "♟️ Chess game"
    VoiceMessage.parseAnyFileOrNull(body)?.let {
        return if (it.mimeType.startsWith("video/")) "🎬 Video" else "📎 File"
    }
    return body
}

/**
 * A row that reveals a leading and/or trailing action button as you drag it open — like iOS's
 * `.swipeActions`, the drag only *reveals* the button; the action itself only runs when you tap
 * the revealed button, never just from completing the drag motion (unlike Material3's
 * `SwipeToDismissBox`, whose `confirmValueChange` fires as soon as the swipe crosses its
 * threshold, with no separate tap step).
 */
@Composable
fun SwipeActionRow(
    enabled: Boolean = true,
    // Matches the content's own corner radius so the leading/trailing action color underneath
    // gets clipped to the same rounded shape — otherwise its sharp corners peek out past the
    // content's rounded ones even at rest (offsetX == 0), showing as a stray sliver of color.
    cornerRadius: Dp = 0.dp,
    leadingIcon: ImageVector? = null,
    leadingLabel: String? = null,
    leadingColor: Color = Color.Transparent,
    onLeadingClick: () -> Unit = {},
    trailingIcon: ImageVector,
    trailingLabel: String,
    trailingColor: Color,
    onTrailingClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val actionWidthDp = 88.dp
    val actionWidthPx = with(density) { actionWidthDp.toPx() }
    val hasLeading = leadingIcon != null
    var offsetX by remember { mutableStateOf(0f) }

    val draggableState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(-actionWidthPx, if (hasLeading) actionWidthPx else 0f)
    }

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(cornerRadius))) {
        Row(modifier = Modifier.matchParentSize()) {
            if (hasLeading) {
                Box(
                    modifier = Modifier
                        .width(actionWidthDp)
                        .fillMaxHeight()
                        .background(leadingColor)
                        .clickable(enabled = offsetX > 1f) {
                            onLeadingClick()
                            offsetX = 0f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(leadingIcon!!, contentDescription = leadingLabel, tint = Color.Black)
                        Text(leadingLabel ?: "", color = Color.Black, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .width(actionWidthDp)
                    .fillMaxHeight()
                    .background(trailingColor)
                    .clickable(enabled = offsetX < -1f) {
                        onTrailingClick()
                        offsetX = 0f
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(trailingIcon, contentDescription = trailingLabel, tint = LocalAppColors.current.textPrimary)
                    Text(trailingLabel, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .then(
                    if (enabled) {
                        Modifier.draggable(
                            state = draggableState,
                            orientation = Orientation.Horizontal,
                            onDragStopped = {
                                val target = when {
                                    offsetX > actionWidthPx / 2 -> actionWidthPx
                                    offsetX < -actionWidthPx / 2 -> -actionWidthPx
                                    else -> 0f
                                }
                                animate(initialValue = offsetX, targetValue = target) { value, _ -> offsetX = value }
                            }
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
            // While revealed, tapping the row itself closes it rather than firing its normal
            // click/select action underneath — matches the reference apps' swipe-action rows.
            if (kotlin.math.abs(offsetX) > 1f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            offsetX = 0f
                        }
                )
            }
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) offsetX = 0f
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            imageUrl = convo.contact.knsAvatarUrl,
            deviceContactPhotoUri = convo.contact.systemContactPhotoUri,
            backupPhotoBase64 = convo.contact.backupPhotoBase64,
            fallbackText = convo.contact.avatarFallbackText,
            size = 48.dp
        )

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            val contactLabel = convo.contact.displayName
            Text(
                text = contactLabel,
                style = MaterialTheme.typography.titleMedium,
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold
            )
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
            Text(
                text = when {
                    reactionPreview != null -> reactionPreview
                    convo.contact.conversationStatus == "pending" -> "🤝 ${messagePreviewText(convo.lastMessage, contactLabel) ?: "Wants to connect"}"
                    else -> messagePreviewText(convo.lastMessage, contactLabel) ?: "No messages yet"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (convo.contact.conversationStatus == "pending") KaspaTeal else Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (convo.unreadCount > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                    .background(KaspaTeal, CircleShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = convo.unreadCount.toString(),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Contact avatar — the single place the app's avatar resolution order lives, used everywhere a
 * contact is shown:
 *
 *   1. [imageUrl] — the contact's KNS profile photo (a remote https URL), when they have one.
 *   2. [deviceContactPhotoUri] — the photo from the device address book for a linked phone
 *      contact (a local `content://` URI; see [com.kachat.app.models.ContactEntity.systemContactPhotoUri]).
 *   3. the person glyph.
 *
 * Both image steps go through Coil, so the memory/disk caches and the off-main-thread decode are
 * the same for a device photo as for a KNS avatar. A candidate that fails to load falls through to
 * the next one rather than dead-ending on the glyph — that's what makes a broken/expired KNS URL
 * still show the device photo.
 *
 * Call sites should pass BOTH sources rather than pre-collapsing them, so the fallback order stays
 * defined here and can't drift per screen.
 */
@Composable
fun ContactAvatar(
    imageUrl: String?,
    fallbackText: String,
    size: Dp,
    modifier: Modifier = Modifier,
    backgroundColor: Color = LocalAppColors.current.surface,
    fontSize: TextUnit = 16.sp,
    deviceContactPhotoUri: String? = null,
    backupPhotoBase64: String? = null
) {
    val candidates = remember(imageUrl, deviceContactPhotoUri) {
        listOfNotNull(
            imageUrl?.takeIf { it.isNotBlank() },
            deviceContactPhotoUri?.takeIf { it.isNotBlank() }
        )
    }
    // Cross-platform backup photo (base64 JPEG) decoded once; the last fallback before the glyph.
    val backupBitmap = remember(backupPhotoBase64) { decodeBase64Avatar(backupPhotoBase64) }
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

private fun decodeBase64Avatar(base64: String?): ImageBitmap? {
    if (base64.isNullOrBlank()) return null
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/** Renders [candidates] in order, dropping to the next on load failure; then the backup photo, then the glyph. */
@Composable
private fun AvatarImageChain(candidates: List<String>, fallbackText: String, fontSize: TextUnit, backupBitmap: ImageBitmap? = null) {
    val current = candidates.firstOrNull()
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
    SubcomposeAsyncImage(
        model = current,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        loading = { AvatarInitials(fallbackText, fontSize) },
        error = { AvatarImageChain(candidates.drop(1), fallbackText, fontSize, backupBitmap) }
    )
}

@Composable
private fun AvatarInitials(text: String, fontSize: TextUnit) {
    // 4.0 (matches iOS): no photo shows a person glyph, not initials - initials read like
    // random letters for KNS-less addresses and looked inconsistent next to real avatars.
    Icon(
        imageVector = Icons.Outlined.Person,
        contentDescription = null,
        tint = KaspaTeal,
        modifier = Modifier.fillMaxSize(0.55f)
    )
}
