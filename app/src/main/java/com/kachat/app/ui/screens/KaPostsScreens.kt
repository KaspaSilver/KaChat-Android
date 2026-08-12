package com.kachat.app.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.models.KaPostDraft
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.KaPostsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Cross-screen deep-link handoff: MainActivity (kachat://kapost/<txid>, universal links) and
 * notification taps set this; KaPostsScreen consumes it and opens the post's thread.
 */
object KaPostsDeepLink {
    val pendingPostTxId = MutableStateFlow<String?>(null)
}

// MARK: - Main screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaPostsScreen(
    navController: NavController,
    viewModel: KaPostsViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val selectedFeed by viewModel.selectedFeed.collectAsState()
    val feed by viewModel.visibleFeed.collectAsState()
    val isLoading by viewModel.isLoadingFeed.collectAsState()
    val feedError by viewModel.feedError.collectAsState()
    val undoToast by viewModel.undoToast.collectAsState()
    val actionToast by viewModel.actionToast.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current

    var showSideMenu by remember { mutableStateOf(false) }
    var showComposer by remember { mutableStateOf(false) }
    // Zero-balance funding gate — tapping "New post" while the chatting balance is a confirmed
    // 0 KAS opens the shared funding card as a dialog instead of the post composer (replies get
    // the same treatment inside KaPostThreadOverlay). See GiftClaimUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()
    var showFundingGate by remember { mutableStateOf(false) }
    /** Thread stack: each entry is a post's LOCAL id; tapping nested comments pushes deeper. */
    var threadStack by remember { mutableStateOf(listOf<String>()) }
    var repostTarget by remember { mutableStateOf<KaPostDraft?>(null) }
    var quoteTarget by remember { mutableStateOf<KaPostDraft?>(null) }
    var engagementTarget by remember { mutableStateOf<KaPostDraft?>(null) }
    var showMyProfile by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var followListKind by remember { mutableStateOf<Boolean?>(null) } // true = followers
    var moderationKind by remember { mutableStateOf<Boolean?>(null) } // true = blocked
    var showBookmarks by remember { mutableStateOf(false) }
    var notFoundNotice by remember { mutableStateOf(false) }

    val posterProfile by viewModel.posterProfile.collectAsState()
    val deepLinkTxId by KaPostsDeepLink.pendingPostTxId.collectAsState()

    fun openThread(post: KaPostDraft) {
        threadStack = threadStack + post.id
    }

    fun openShared(txId: String) {
        scope.launch {
            val post = viewModel.openSharedPost(txId)
            if (post != null) {
                openThread(post)
            } else {
                notFoundNotice = true
                delay(3_000)
                notFoundNotice = false
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.loadFeed() }
    LaunchedEffect(deepLinkTxId) {
        val txId = deepLinkTxId ?: return@LaunchedEffect
        KaPostsDeepLink.pendingPostTxId.value = null
        openShared(txId)
    }

    val repostHandler: (KaPostDraft) -> Unit = { post ->
        if (post.remoteId != null && post.posterPubkey != null) repostTarget = post
    }

    Scaffold(
        containerColor = colors.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (fundingGate.active) showFundingGate = true else showComposer = true },
                containerColor = KaspaTeal,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New post")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showSideMenu = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = colors.textPrimary)
                    }
                    Text(
                        text = "KaPosts",
                        color = colors.textPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color = KaspaTeal,
                        )
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = KaspaTeal)
                        }
                    }
                }
                FeedTabsRow(selected = selectedFeed, onSelect = { viewModel.selectFeed(it) })
                HorizontalDivider(color = colors.surfaceVariant)

                if (feedError != null && feed.isEmpty()) {
                    FeedEmptyState(
                        title = "Couldn't load the feed",
                        body = feedError ?: "",
                        actionLabel = "Retry",
                        onAction = { viewModel.refresh() },
                    )
                } else if (feed.isEmpty() && !isLoading) {
                    FeedEmptyState(
                        title = if (selectedFeed == KaPostsViewModel.FeedTab.FOLLOWING) "Nothing here yet" else "No posts yet",
                        body = if (selectedFeed == KaPostsViewModel.FeedTab.FOLLOWING)
                            "Follow people from their posts and their content shows up here."
                        else
                            "Be the first to post something on the Kaspa network.",
                        actionLabel = null,
                        onAction = {},
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(feed, key = { it.id }) { post ->
                            LaunchedEffect(post.posterAddress) {
                                viewModel.ensureSenderProfileFetched(post.posterAddress)
                            }
                            KaPostCell(
                                post = post,
                                viewModel = viewModel,
                                onOpenThread = { openThread(post) },
                                onRepostTap = { repostHandler(post) },
                                onOpenProfile = { viewModel.openPosterProfile(post.posterAddress, post.posterPubkey) },
                                onOpenQuoted = { txId -> openShared(txId) },
                                onViewEngagement = { engagementTarget = post },
                                truncatesLongText = true,
                            )
                            HorizontalDivider(
                                color = colors.surfaceVariant,
                                modifier = Modifier.padding(start = 68.dp),
                            )
                        }
                    }
                }
            }

            // Left slide-out menu.
            AnimatedVisibility(visible = showSideMenu, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { showSideMenu = false },
                )
            }
            AnimatedVisibility(
                visible = showSideMenu,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it }),
            ) {
                KaPostsSideMenu(
                    onProfile = { showSideMenu = false; showMyProfile = true; viewModel.loadMyProfile() },
                    onNotifications = { showSideMenu = false; showNotifications = true },
                    onBookmarks = { showSideMenu = false; showBookmarks = true },
                    onMuted = { showSideMenu = false; moderationKind = false },
                    onBlocked = { showSideMenu = false; moderationKind = true },
                )
            }

            if (notFoundNotice) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 90.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.surfaceVariant, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        "Post not found - it may be older than the feed window.",
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                    )
                }
            }

            KaPostsToastOverlay(
                undoToast = undoToast,
                actionToast = actionToast,
                onUndo = { viewModel.undoPendingPost() },
                onViewTx = { uriHandler.openUri(kaspaExplorer.txUrl(it)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    }

    // Also conditioned on the gate itself so the dialog vanishes reactively the moment the
    // chatting balance confirms as funded (e.g. the gift claim lands while it's open).
    if (showFundingGate && fundingGate.active) {
        ZeroBalanceFundingDialog(
            walletAddress = fundingGate.chattingAddress,
            onDismiss = { showFundingGate = false },
        )
    }

    if (showComposer) {
        KaPostComposerDialog(
            title = "New Post",
            quoted = null,
            onDismiss = { showComposer = false },
            onSubmit = { text ->
                showComposer = false
                viewModel.schedulePost(text)
            },
        )
    }

    quoteTarget?.let { target ->
        KaPostComposerDialog(
            title = "Quote Post",
            quoted = target,
            quotedDisplayName = viewModel.posterDisplayName(target.posterAddress),
            onDismiss = { quoteTarget = null },
            onSubmit = { text ->
                quoteTarget = null
                viewModel.scheduleQuote(target, text)
            },
        )
    }

    repostTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { repostTarget = null },
            containerColor = colors.surface,
            title = { Text("Repost", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Share this post to your followers.", color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    repostTarget = null
                    viewModel.scheduleRepost(target)
                }) { Text("Repost", color = KaspaTeal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { repostTarget = null }) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                    TextButton(onClick = {
                        quoteTarget = target
                        repostTarget = null
                    }) { Text("Quote", color = KaspaTeal) }
                }
            },
        )
    }

    // Thread stack - the topmost id renders; back pops.
    threadStack.lastOrNull()?.let { topId ->
        val post = viewModel.findPost(topId)
        if (post != null) {
            KaPostThreadOverlay(
                post = post,
                viewModel = viewModel,
                onClose = { threadStack = threadStack.dropLast(1) },
                onOpenNested = { nested -> openThread(nested) },
                onOpenProfile = { address, pubkey -> viewModel.openPosterProfile(address, pubkey) },
                onOpenShared = { txId -> openShared(txId) },
                onRepostTap = { repostHandler(it) },
                onViewEngagement = { engagementTarget = it },
            )
        } else {
            threadStack = threadStack.dropLast(1)
        }
    }

    if (showMyProfile) {
        KaPostsProfileOverlay(
            address = viewModel.myAddress() ?: "",
            pubkey = null,
            isMine = true,
            viewModel = viewModel,
            navController = navController,
            onClose = { showMyProfile = false },
            onOpenThread = { openThread(it) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            onOpenQuoted = { openShared(it) },
            onOpenFollowList = { followListKind = it },
        )
    }

    posterProfile?.let { profile ->
        KaPostsProfileOverlay(
            address = profile.address,
            pubkey = profile.pubkey,
            isMine = false,
            viewModel = viewModel,
            navController = navController,
            onClose = { viewModel.closePosterProfile() },
            onOpenThread = { openThread(it) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            onOpenQuoted = { openShared(it) },
            onOpenFollowList = null,
        )
    }

    if (showNotifications) {
        KaPostsNotificationsOverlay(
            viewModel = viewModel,
            onClose = { showNotifications = false },
            onOpenPost = { txId ->
                showNotifications = false
                openShared(txId)
            },
        )
    }

    followListKind?.let { followers ->
        KaPostsFollowListOverlay(
            followers = followers,
            viewModel = viewModel,
            onClose = { followListKind = null },
        )
    }

    engagementTarget?.let { post ->
        KaPostEngagementOverlay(
            post = post,
            viewModel = viewModel,
            onClose = { engagementTarget = null },
        )
    }

    moderationKind?.let { blocked ->
        KaPostsModerationOverlay(
            blocked = blocked,
            viewModel = viewModel,
            onClose = { moderationKind = null },
        )
    }

    if (showBookmarks) {
        KaPostsBookmarksOverlay(
            viewModel = viewModel,
            onClose = { showBookmarks = false },
            onOpenThread = {
                showBookmarks = false
                openThread(it)
            },
        )
    }
}

// MARK: - Side menu

@Composable
private fun KaPostsSideMenu(
    onProfile: () -> Unit,
    onNotifications: () -> Unit,
    onBookmarks: () -> Unit,
    onMuted: () -> Unit,
    onBlocked: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(colors.surface)
            .statusBarsPadding()
            .padding(vertical = 16.dp),
    ) {
        SideMenuRow(Icons.Outlined.Person, "Profile", onProfile)
        SideMenuRow(Icons.Default.NotificationsNone, "Notifications", onNotifications)
        SideMenuRow(Icons.Default.BookmarkBorder, "Bookmarks", onBookmarks)
        SideMenuRow(Icons.Default.VolumeOff, "Muted", onMuted)
        SideMenuRow(Icons.Default.Block, "Blocked", onBlocked)
    }
}

@Composable
private fun SideMenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FeedTabsRow(
    selected: KaPostsViewModel.FeedTab,
    onSelect: (KaPostsViewModel.FeedTab) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf(
            KaPostsViewModel.FeedTab.FOLLOWING to "Following",
            KaPostsViewModel.FeedTab.FEED to "Feed",
            KaPostsViewModel.FeedTab.POPULAR to "Popular",
        ).forEach { (tab, label) ->
            val isSelected = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) KaspaTeal else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun FeedEmptyState(title: String, body: String, actionLabel: String?, onAction: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, color = colors.textSecondary, fontSize = 14.sp)
        if (actionLabel != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel, color = KaspaTeal, fontWeight = FontWeight.Bold) }
        }
    }
}

// MARK: - Post cell

@Composable
fun KaPostCell(
    post: KaPostDraft,
    viewModel: KaPostsViewModel,
    onOpenThread: () -> Unit,
    onRepostTap: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenQuoted: (String) -> Unit = {},
    onViewEngagement: () -> Unit = {},
    isRoot: Boolean = false,
    /**
     * Feed cells fold very long posts behind "Show more" (which opens the full thread);
     * detail/comment/profile/bookmark cells show everything. Matches iOS KaPostCellView.
     */
    truncatesLongText: Boolean = false,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val senderProfiles by viewModel.senderProfiles.collectAsState()
    val senderKnsNames by viewModel.senderKnsNames.collectAsState()
    val contactAliases by viewModel.contactAliases.collectAsState()
    val following by viewModel.following.collectAsState()
    val deadlines by viewModel.undoDeadlines.collectAsState()
    var showOverflow by remember { mutableStateOf(false) }

    val name = contactAliases[post.posterAddress]?.takeIf { it.isNotBlank() }?.let { viewModel.strippingKasSuffix(it) }
        ?: senderKnsNames[post.posterAddress]?.takeIf { it.isNotBlank() }?.let { viewModel.strippingKasSuffix(it) }
        ?: post.posterAddress.takeLast(10)
    val isMine = post.posterAddress == viewModel.myAddress()
    // Long enough that the feed should fold it (X-style ~280-char threshold, or a wall of
    // newlines) - same numbers as iOS's KaPostCellView.isLongPost.
    val isLongPost = post.text.length > 280 || post.text.count { it == '\n' } >= 8
    val foldText = truncatesLongText && isLongPost

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isRoot) { onOpenThread() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.clickable { onOpenProfile() }) {
                ContactAvatar(
                    imageUrl = senderProfiles[post.posterAddress],
                    fallbackText = name,
                    size = 42.dp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable { onOpenProfile() },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = relativePostTime(post.timestamp),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    if (!isMine) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val isFollowing = post.posterAddress in following
                        Text(
                            text = if (isFollowing) "Following" else "Follow",
                            color = if (isFollowing) colors.textSecondary else KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                viewModel.toggleFollow(post.posterAddress, post.posterPubkey)
                            },
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box {
                        Icon(
                            Icons.Default.MoreHoriz,
                            contentDescription = "More",
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { showOverflow = true },
                        )
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            if (post.remoteId != null) {
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.shareText(post)?.let { text ->
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, text)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share Post"))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Post Activity") },
                                    leadingIcon = { Icon(Icons.Outlined.BarChart, null) },
                                    onClick = {
                                        showOverflow = false
                                        onViewEngagement()
                                    },
                                )
                            }
                            if (!isMine) {
                                DropdownMenuItem(
                                    text = { Text("Mute") },
                                    leadingIcon = { Icon(Icons.Default.VolumeOff, null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.mute(post.posterAddress)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Block") },
                                    leadingIcon = { Icon(Icons.Default.Block, null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.block(post.posterAddress)
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = post.text,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    maxLines = if (foldText) 8 else Int.MAX_VALUE,
                    overflow = if (foldText) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
                if (foldText) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Show more",
                        color = KaspaTeal,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onOpenThread() },
                    )
                }
                post.quoted?.let { quoted ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.clickable(enabled = quoted.remoteId != null) {
                            quoted.remoteId?.let(onOpenQuoted)
                        },
                    ) {
                        QuotedEmbedCard(
                            quoted = quoted,
                            displayName = viewModel.posterDisplayName(quoted.posterAddress),
                        )
                    }
                }
                when (post.deliveryStatus) {
                    KaPostDraft.Delivery.PENDING -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Posting…", color = colors.textSecondary, fontSize = 12.sp)
                    }
                    KaPostDraft.Delivery.FAILED -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            Text("Failed to post.", color = Color(0xFFE57373), fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Retry",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { viewModel.retryPost(post) },
                            )
                        }
                    }
                    KaPostDraft.Delivery.SENT -> Unit
                }
                Spacer(modifier = Modifier.height(8.dp))
                EngagementRow(
                    post = post,
                    commentCount = viewModel.commentCount(post),
                    deadlines = deadlines,
                    onComment = onOpenThread,
                    onRepost = onRepostTap,
                    onLike = { viewModel.toggleLike(post) },
                    onDislike = { viewModel.toggleDislike(post) },
                    onBookmark = { viewModel.toggleBookmark(post) },
                    onCancelCountdown = { viewModel.cancelUndoable(it) },
                )
            }
        }
    }
}

@Composable
private fun QuotedEmbedCard(quoted: KaPostDraft.QuotedRef, displayName: String) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Text(
            text = displayName,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = quoted.text.ifBlank { "Reposted" },
            color = colors.textSecondary,
            fontSize = 13.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Engagement row with in-icon undo countdowns

@Composable
private fun EngagementRow(
    post: KaPostDraft,
    commentCount: Int,
    deadlines: Map<String, Long>,
    onComment: () -> Unit,
    onRepost: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onBookmark: () -> Unit,
    onCancelCountdown: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EngagementAction(
            countdownKey = null,
            deadlines = deadlines,
            icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp)) },
            count = commentCount,
            onTap = onComment,
            onCancel = onCancelCountdown,
        )
        Spacer(modifier = Modifier.weight(1f))
        EngagementAction(
            countdownKey = "repost:${post.id}",
            deadlines = deadlines,
            icon = {
                Icon(
                    Icons.Default.Repeat, null,
                    tint = if (post.repostedByMe) KaspaTeal else colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            },
            count = post.reposts,
            onTap = onRepost,
            onCancel = onCancelCountdown,
        )
        Spacer(modifier = Modifier.weight(1f))
        EngagementAction(
            countdownKey = "like:${post.id}",
            deadlines = deadlines,
            icon = {
                Icon(
                    if (post.likedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                    tint = if (post.likedByMe) Color(0xFFE0245E) else colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            },
            count = post.likes,
            onTap = onLike,
            onCancel = onCancelCountdown,
        )
        Spacer(modifier = Modifier.weight(1f))
        EngagementAction(
            countdownKey = "dislike:${post.id}",
            deadlines = deadlines,
            icon = {
                Icon(
                    if (post.dislikedByMe) Icons.Outlined.ThumbDown else Icons.Outlined.ThumbDownOffAlt, null,
                    tint = if (post.dislikedByMe) Color(0xFF7E57C2) else colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            },
            count = post.dislikes,
            onTap = onDislike,
            onCancel = onCancelCountdown,
        )
        Spacer(modifier = Modifier.weight(1f))
        EngagementAction(
            countdownKey = null,
            deadlines = deadlines,
            icon = {
                Icon(
                    if (post.bookmarkedByMe) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null,
                    tint = if (post.bookmarkedByMe) KaspaTeal else colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            },
            count = null,
            onTap = onBookmark,
            onCancel = onCancelCountdown,
        )
    }
}

@Composable
private fun EngagementAction(
    countdownKey: String?,
    deadlines: Map<String, Long>,
    icon: @Composable () -> Unit,
    count: Int?,
    onTap: () -> Unit,
    onCancel: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val deadline = countdownKey?.let { deadlines[it] }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                if (deadline != null && countdownKey != null) onCancel(countdownKey) else onTap()
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        if (deadline != null) {
            CountdownBadge(deadlineMs = deadline)
        } else {
            icon()
        }
        if (count != null && count > 0) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = formatEngagementCount(count),
                color = colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

/** Live "5…4…3" ring shown in an icon's place while its undo window runs. Tap = cancel. */
@Composable
private fun CountdownBadge(deadlineMs: Long) {
    var remainingMs by remember(deadlineMs) { mutableLongStateOf(deadlineMs - System.currentTimeMillis()) }
    LaunchedEffect(deadlineMs) {
        while (remainingMs > 0) {
            delay(100)
            remainingMs = deadlineMs - System.currentTimeMillis()
        }
    }
    val fraction = (remainingMs.coerceAtLeast(0).toFloat() / KaPostsViewModel.UNDO_DELAY_MS).coerceIn(0f, 1f)
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = Color(0xFFFFA726),
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Text(text = "$seconds", color = Color(0xFFFFA726), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// MARK: - Composer with the 25k ring meter

@Composable
fun KaPostComposerDialog(
    title: String,
    quoted: KaPostDraft?,
    quotedDisplayName: String = "",
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    var text by remember { mutableStateOf("") }
    val limit = KaPostDraft.POST_CHARACTER_LIMIT
    val canPost = text.isNotBlank() && text.length <= limit

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel", tint = KaspaTeal)
                }
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                KaPostCharacterMeter(count = text.length)
                Spacer(modifier = Modifier.width(10.dp))
                TextButton(onClick = { onSubmit(text.trim()) }, enabled = canPost) {
                    Text(
                        "Post",
                        color = if (canPost) KaspaTeal else colors.textSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            HorizontalDivider(color = colors.surfaceVariant)
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= limit) text = it },
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(KaspaTeal),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text("What's happening on Kaspa?", color = colors.textSecondary, fontSize = 16.sp)
                    }
                    inner()
                },
            )
            quoted?.let {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    QuotedEmbedCard(
                        quoted = KaPostDraft.QuotedRef(
                            remoteId = it.remoteId,
                            text = it.text,
                            posterAddress = it.posterAddress,
                            timestamp = it.timestamp,
                        ),
                        displayName = quotedDisplayName,
                    )
                }
            }
        }
    }
}

/**
 * X-style ring meter: fills toward the 25,000-character limit, flips orange in the final 10%
 * with a live remaining count, red at the wall. Hidden while empty.
 */
@Composable
fun KaPostCharacterMeter(count: Int) {
    if (count == 0) return
    val colors = LocalAppColors.current
    val limit = KaPostDraft.POST_CHARACTER_LIMIT
    val progress = (count.toFloat() / limit).coerceIn(0f, 1f)
    val remaining = limit - count
    val nearLimit = progress >= 0.9f
    val ringColor = when {
        remaining <= 0 -> Color(0xFFE53935)
        nearLimit -> Color(0xFFFFA726)
        else -> KaspaTeal
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (nearLimit) {
            Text(
                text = "$remaining",
                color = ringColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Canvas(modifier = Modifier.size(20.dp)) {
            drawCircle(
                color = colors.surfaceVariant,
                style = Stroke(width = 2.5.dp.toPx()),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

// MARK: - Thread overlay (root + replies, X-style inline nested expansion)

@Composable
fun KaPostThreadOverlay(
    post: KaPostDraft,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    onOpenNested: (KaPostDraft) -> Unit,
    onOpenProfile: (String, String?) -> Unit,
    onOpenShared: (String) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
) {
    val colors = LocalAppColors.current
    var replyText by remember { mutableStateOf("") }
    // Zero-balance funding gate — tapping the reply composer while the chatting balance is a
    // confirmed 0 KAS opens the shared funding card instead of the reply field/keyboard.
    val fundingGate = rememberZeroBalanceFundingGate()
    var showFundingGate by remember { mutableStateOf(false) }
    var expandedIds by remember { mutableStateOf(setOf<String>()) }
    val muted by viewModel.muted.collectAsState()
    val blocked by viewModel.blocked.collectAsState()
    val hidden = muted + blocked
    val visibleComments = post.comments.filter { it.posterAddress !in hidden }

    LaunchedEffect(post.remoteId) { viewModel.loadReplies(post) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = KaspaTeal)
                }
                Text("Post", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            HorizontalDivider(color = colors.surfaceVariant)
            LazyColumn(modifier = Modifier.weight(1f)) {
                item(key = "root-context") {
                    // "Replying to X" - this post is itself a reply; tap opens the parent.
                    post.parentRemoteId?.let { parentId ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenShared(parentId) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text("Replying to a post - view it", color = KaspaTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider(color = colors.surfaceVariant)
                    }
                }
                item(key = post.id) {
                    KaPostCell(
                        post = post,
                        viewModel = viewModel,
                        onOpenThread = {},
                        onRepostTap = { onRepostTap(post) },
                        onOpenProfile = { onOpenProfile(post.posterAddress, post.posterPubkey) },
                        onOpenQuoted = onOpenShared,
                        onViewEngagement = { onViewEngagement(post) },
                        isRoot = true,
                    )
                    HorizontalDivider(color = colors.surfaceVariant)
                }
                items(visibleComments, key = { it.id }) { comment ->
                    LaunchedEffect(comment.posterAddress) {
                        viewModel.ensureSenderProfileFetched(comment.posterAddress)
                    }
                    ThreadCommentNode(
                        comment = comment,
                        depth = 0,
                        expandedIds = expandedIds,
                        hidden = hidden,
                        viewModel = viewModel,
                        onToggleExpand = { id ->
                            expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
                        },
                        onOpenNested = onOpenNested,
                        onOpenProfile = onOpenProfile,
                        onOpenShared = onOpenShared,
                        onRepostTap = onRepostTap,
                        onViewEngagement = onViewEngagement,
                    )
                    HorizontalDivider(
                        color = colors.surfaceVariant,
                        modifier = Modifier.padding(start = 88.dp),
                    )
                }
            }
            HorizontalDivider(color = colors.surfaceVariant)
            // While the funding gate is active the reply row renders dimmed and any tap on it
            // opens the funding card instead of focusing the field — same "no composer until
            // funded" rule as the New Post FAB above.
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .alpha(if (fundingGate.active) 0.35f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = replyText,
                        onValueChange = { if (it.length <= KaPostDraft.POST_CHARACTER_LIMIT) replyText = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(KaspaTeal),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surface)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            if (replyText.isEmpty()) {
                                Text("Post your reply", color = colors.textSecondary, fontSize = 15.sp)
                            }
                            inner()
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    KaPostCharacterMeter(count = replyText.length)
                    TextButton(
                        onClick = {
                            viewModel.submitReply(post, replyText.trim())
                            replyText = ""
                        },
                        enabled = replyText.isNotBlank(),
                    ) {
                        Text(
                            "Reply",
                            color = if (replyText.isNotBlank()) KaspaTeal else colors.textSecondary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (fundingGate.active) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showFundingGate = true }
                    )
                }
            }
        }
    }

    // Also conditioned on the gate itself so the dialog vanishes reactively the moment the
    // chatting balance confirms as funded (e.g. the gift claim lands while it's open).
    if (showFundingGate && fundingGate.active) {
        ZeroBalanceFundingDialog(
            walletAddress = fundingGate.chattingAddress,
            onDismiss = { showFundingGate = false },
        )
    }
}

/**
 * One comment with X-style inline expansion: "View N replies" loads and indents its children
 * (connector line at the leading edge), recursively. Tapping the comment itself pushes it as
 * a new thread root for full depth.
 */
@Composable
private fun ThreadCommentNode(
    comment: KaPostDraft,
    depth: Int,
    expandedIds: Set<String>,
    hidden: Set<String>,
    viewModel: KaPostsViewModel,
    onToggleExpand: (String) -> Unit,
    onOpenNested: (KaPostDraft) -> Unit,
    onOpenProfile: (String, String?) -> Unit,
    onOpenShared: (String) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
) {
    val colors = LocalAppColors.current
    val expanded = comment.id in expandedIds
    val childCount = maxOf(comment.remoteReplyCount, comment.comments.count { it.posterAddress !in hidden })

    Column(modifier = Modifier.padding(start = (20 + depth * 24).dp)) {
        KaPostCell(
            post = comment,
            viewModel = viewModel,
            onOpenThread = { onOpenNested(comment) },
            onRepostTap = { onRepostTap(comment) },
            onOpenProfile = { onOpenProfile(comment.posterAddress, comment.posterPubkey) },
            onOpenQuoted = onOpenShared,
            onViewEngagement = { onViewEngagement(comment) },
        )
        if (childCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable {
                        if (!expanded) viewModel.expandReplies(comment)
                        onToggleExpand(comment.id)
                    }
                    .padding(start = 56.dp, top = 2.dp, bottom = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.surfaceVariant),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expanded) "Hide replies"
                    else if (childCount == 1) "View 1 reply"
                    else "View $childCount replies",
                    color = KaspaTeal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (expanded) {
            val children = comment.comments.filter { it.posterAddress !in hidden }
            if (children.isEmpty()) {
                Row(modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = KaspaTeal)
                }
            } else {
                children.forEach { child ->
                    LaunchedEffect(child.posterAddress) {
                        viewModel.ensureSenderProfileFetched(child.posterAddress)
                    }
                    ThreadCommentNode(
                        comment = child,
                        depth = depth + 1,
                        expandedIds = expandedIds,
                        hidden = hidden,
                        viewModel = viewModel,
                        onToggleExpand = onToggleExpand,
                        onOpenNested = onOpenNested,
                        onOpenProfile = onOpenProfile,
                        onOpenShared = onOpenShared,
                        onRepostTap = onRepostTap,
                        onViewEngagement = onViewEngagement,
                    )
                }
            }
        }
    }
}

// MARK: - Profile overlay (mine + tapped poster)

@Composable
fun KaPostsProfileOverlay(
    address: String,
    pubkey: String?,
    isMine: Boolean,
    viewModel: KaPostsViewModel,
    navController: NavController,
    onClose: () -> Unit,
    onOpenThread: (KaPostDraft) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
    onOpenQuoted: (String) -> Unit,
    onOpenFollowList: ((Boolean) -> Unit)?,
) {
    val colors = LocalAppColors.current
    val senderProfiles by viewModel.senderProfiles.collectAsState()
    val senderBanners by viewModel.senderBanners.collectAsState()
    val senderBios by viewModel.senderBios.collectAsState()
    val following by viewModel.following.collectAsState()
    val posterProfile by viewModel.posterProfile.collectAsState()
    val myFollowersCount by viewModel.myFollowersCount.collectAsState()
    val isLoadingMyProfile by viewModel.isLoadingMyProfile.collectAsState()
    val myProfileReplies by viewModel.myProfileReplies.collectAsState()
    val posterPosts by viewModel.posterProfilePosts.collectAsState()
    val posterReplies by viewModel.posterProfileReplies.collectAsState()
    // Recompose against the live lists so engagement changes show immediately.
    val localPosts by viewModel.localPosts.collectAsState()
    val myPostsList = if (isMine) viewModel.myCombinedPosts() else posterPosts
    val repliesList = if (isMine) myProfileReplies else posterReplies

    var selectedTab by remember { mutableStateOf(0) } // 0 = Posts, 1 = Replies
    val name = viewModel.posterDisplayName(address)

    LaunchedEffect(address) { viewModel.ensureSenderProfileFetched(address) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item(key = "banner") {
                    Box {
                        val bannerUrl = senderBanners[address]
                        if (bannerUrl != null) {
                            SubcomposeAsyncImage(
                                model = bannerUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                loading = { Box(Modifier.fillMaxSize().background(colors.surfaceVariant)) },
                                error = { Box(Modifier.fillMaxSize().background(colors.surfaceVariant)) },
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(140.dp).background(colors.surfaceVariant))
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.35f)),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Box(modifier = Modifier.offset(y = (-38).dp)) {
                            Box(
                                modifier = Modifier
                                    .size(82.dp)
                                    .clip(CircleShape)
                                    .background(colors.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                ContactAvatar(
                                    imageUrl = senderProfiles[address],
                                    fallbackText = name,
                                    size = 76.dp,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (!isMine) {
                            val isFollowing = address in following
                            TextButton(onClick = { viewModel.toggleFollow(address, pubkey) }) {
                                Text(
                                    if (isFollowing) "Following" else "Follow",
                                    color = if (isFollowing) colors.textSecondary else KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            TextButton(onClick = {
                                viewModel.ensureContactExists(address) { contactId ->
                                    onClose()
                                    navController.navigate("chat/$contactId")
                                }
                            }) {
                                Text("Chat", color = KaspaTeal, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                item(key = "header") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp).offset(y = (-26).dp)) {
                        Text(name, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
                        senderBios[address]?.let { bio ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(bio, color = colors.textSecondary, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row {
                            val followingCount = if (isMine) following.size else posterProfile?.followingCount ?: 0
                            val followersCount = if (isMine) (myFollowersCount ?: 0) else posterProfile?.followersCount ?: 0
                            Row(
                                modifier = Modifier.clickable(enabled = onOpenFollowList != null) { onOpenFollowList?.invoke(false) },
                            ) {
                                Text("$followingCount", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Following", color = colors.textSecondary, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Row(
                                modifier = Modifier.clickable(enabled = onOpenFollowList != null) { onOpenFollowList?.invoke(true) },
                            ) {
                                Text("$followersCount", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Followers", color = colors.textSecondary, fontSize = 14.sp)
                            }
                        }
                    }
                }
                item(key = "tabs") {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("Posts", "Replies").forEachIndexed { index, label ->
                            val isSelected = index == selectedTab
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedTab = index }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    label,
                                    color = if (isSelected) colors.textPrimary else colors.textSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 15.sp,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isSelected) KaspaTeal else Color.Transparent),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = colors.surfaceVariant)
                }
                val items = if (selectedTab == 0) myPostsList else repliesList
                if (items.isEmpty()) {
                    item(key = "empty") {
                        val loading = if (isMine) isLoadingMyProfile else posterProfile?.isLoading == true
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (loading) {
                                CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            } else {
                                Text(
                                    if (selectedTab == 0) "No posts yet" else "No replies yet",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    if (selectedTab == 0) "Posts will show up here." else "Replies will show up here.",
                                    color = colors.textSecondary,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                } else {
                    items(items, key = { "profile-${it.id}" }) { post ->
                        KaPostCell(
                            post = post,
                            viewModel = viewModel,
                            onOpenThread = { onOpenThread(post) },
                            onRepostTap = { onRepostTap(post) },
                            onOpenQuoted = onOpenQuoted,
                            onViewEngagement = { onViewEngagement(post) },
                        )
                        HorizontalDivider(
                            color = colors.surfaceVariant,
                            modifier = Modifier.padding(start = 68.dp),
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Notifications overlay

@Composable
fun KaPostsNotificationsOverlay(
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    onOpenPost: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val items by viewModel.notifications.collectAsState()
    val isLoading by viewModel.isLoadingNotifications.collectAsState()
    val senderProfiles by viewModel.senderProfiles.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) { viewModel.loadNotifications() }

    KaPostsOverlayScaffold(title = "Notifications", onClose = onClose) {
        if (isLoading && items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KaspaTeal)
            }
        } else if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.NotificationsNone, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text("Nothing yet", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "When someone likes, replies to or shares your posts, it shows up here.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn {
                items(items, key = { it.id }) { item ->
                    LaunchedEffect(item.actorAddress) {
                        viewModel.ensureSenderProfileFetched(item.actorAddress)
                    }
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.targetTxId != null) {
                                item.targetTxId?.let(onOpenPost)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        ContactAvatar(
                            imageUrl = senderProfiles[item.actorAddress],
                            fallbackText = viewModel.posterDisplayName(item.actorAddress),
                            size = 38.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${viewModel.posterDisplayName(item.actorAddress)} ${notificationActionText(item.kind)}",
                                color = colors.textPrimary,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.snippet?.let {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(it, color = colors.textSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(relativePostTime(item.timestampMs), color = colors.textSecondary, fontSize = 12.sp)
                        }
                        Text(
                            "View",
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { uriHandler.openUri(kaspaExplorer.txUrl(item.id)) },
                        )
                    }
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

private fun notificationActionText(kind: KaPostsViewModel.NotificationItem.Kind): String = when (kind) {
    KaPostsViewModel.NotificationItem.Kind.LIKE -> "liked your post"
    KaPostsViewModel.NotificationItem.Kind.DISLIKE -> "disliked your post"
    KaPostsViewModel.NotificationItem.Kind.REPLY -> "replied to your post"
    KaPostsViewModel.NotificationItem.Kind.QUOTE -> "quoted your post"
    KaPostsViewModel.NotificationItem.Kind.REPOST -> "reposted your post"
    KaPostsViewModel.NotificationItem.Kind.FOLLOW -> "followed you"
    KaPostsViewModel.NotificationItem.Kind.OTHER -> "interacted with your post"
}

// MARK: - Follow list overlay

@Composable
fun KaPostsFollowListOverlay(
    followers: Boolean,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val following by viewModel.following.collectAsState()
    val senderProfiles by viewModel.senderProfiles.collectAsState()
    var entries by remember { mutableStateOf<List<KaPostsViewModel.FollowEntry>?>(null) }

    LaunchedEffect(followers) { entries = viewModel.loadFollowList(followers) }

    KaPostsOverlayScaffold(title = if (followers) "Followers" else "Following", onClose = onClose) {
        val list = entries
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KaspaTeal)
            }
        } else if (list.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.PersonAddAlt1, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    if (followers) "No followers yet" else "Not following anyone yet",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    if (followers) "When someone follows you, they'll show up here."
                    else "Accounts you follow will show up here.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn {
                items(list, key = { it.address }) { entry ->
                    LaunchedEffect(entry.address) { viewModel.ensureSenderProfileFetched(entry.address) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        ContactAvatar(
                            imageUrl = senderProfiles[entry.address],
                            fallbackText = viewModel.posterDisplayName(entry.address),
                            size = 38.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                viewModel.posterDisplayName(entry.address),
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            entry.timestampMs?.let {
                                Text(relativePostTime(it), color = colors.textSecondary, fontSize = 12.sp)
                            }
                        }
                        val isFollowing = entry.address in following
                        TextButton(onClick = { viewModel.toggleFollow(entry.address, entry.pubkey) }) {
                            Text(
                                if (isFollowing) "Unfollow" else (if (followers) "Follow Back" else "Follow"),
                                color = if (isFollowing) colors.textSecondary else KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

// MARK: - Engagement overlay (who liked/disliked/reposted/quoted)

@Composable
fun KaPostEngagementOverlay(
    post: KaPostDraft,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val senderProfiles by viewModel.senderProfiles.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current
    var lists by remember { mutableStateOf<KaPostsViewModel.EngagementLists?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(post.remoteId) {
        lists = viewModel.loadEngagement(post)
        loaded = true
    }

    val tabs = listOf("Likes", "Dislikes", "Reposts", "Quotes")

    KaPostsOverlayScaffold(title = "Post Activity", onClose = onClose) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, label ->
                    val isSelected = index == selectedTab
                    val count = lists?.let {
                        when (index) {
                            0 -> maxOf(post.likes, it.likes.size)
                            1 -> maxOf(post.dislikes, it.dislikes.size)
                            2 -> it.reposts.size
                            else -> it.quotes.size
                        }
                    } ?: 0
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = index }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (count > 0) "$label ($count)" else label,
                            color = if (isSelected) colors.textPrimary else colors.textSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isSelected) KaspaTeal else Color.Transparent),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.surfaceVariant)

            val rows = lists?.let {
                when (selectedTab) {
                    0 -> it.likes
                    1 -> it.dislikes
                    2 -> it.reposts
                    else -> it.quotes
                }
            } ?: emptyList()

            Box(modifier = Modifier.weight(1f)) {
                if (!loaded) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KaspaTeal)
                    }
                } else if (rows.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Outlined.BarChart, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Nothing here yet", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "When someone engages with this post, they'll show up here.",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    LazyColumn {
                        items(rows, key = { it.actionTxId }) { entry ->
                            LaunchedEffect(entry.actorAddress) { viewModel.ensureSenderProfileFetched(entry.actorAddress) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                ContactAvatar(
                                    imageUrl = senderProfiles[entry.actorAddress],
                                    fallbackText = viewModel.posterDisplayName(entry.actorAddress),
                                    size = 38.dp,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        viewModel.posterDisplayName(entry.actorAddress),
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(relativePostTime(entry.timestampMs), color = colors.textSecondary, fontSize = 12.sp)
                                }
                                Text(
                                    "View",
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { uriHandler.openUri(kaspaExplorer.txUrl(entry.actionTxId)) },
                                )
                            }
                            HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.surfaceVariant)
            post.remoteId?.let { txId ->
                Text(
                    "View Post Transaction in Explorer",
                    color = KaspaTeal,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(kaspaExplorer.txUrl(txId)) }
                        .padding(vertical = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

// MARK: - Muted/Blocked + Bookmarks overlays

@Composable
fun KaPostsModerationOverlay(
    blocked: Boolean,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val mutedSet by viewModel.muted.collectAsState()
    val blockedSet by viewModel.blocked.collectAsState()
    val senderProfiles by viewModel.senderProfiles.collectAsState()
    val addresses = (if (blocked) blockedSet else mutedSet).sorted()

    KaPostsOverlayScaffold(title = if (blocked) "Blocked" else "Muted", onClose = onClose) {
        if (addresses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (blocked) "No blocked users" else "No muted users",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Their posts hide everywhere in KaPosts.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn {
                items(addresses, key = { it }) { address ->
                    LaunchedEffect(address) { viewModel.ensureSenderProfileFetched(address) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        ContactAvatar(
                            imageUrl = senderProfiles[address],
                            fallbackText = viewModel.posterDisplayName(address),
                            size = 38.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            viewModel.posterDisplayName(address),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            if (blocked) viewModel.unblock(address) else viewModel.unmute(address)
                        }) {
                            Text(if (blocked) "Unblock" else "Unmute", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Composable
fun KaPostsBookmarksOverlay(
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    onOpenThread: (KaPostDraft) -> Unit,
) {
    val colors = LocalAppColors.current
    // Recompute against the live lists so un-bookmarking updates immediately.
    val localPosts by viewModel.localPosts.collectAsState()
    val feed by viewModel.visibleFeed.collectAsState()
    val bookmarks = viewModel.bookmarkedPosts()

    KaPostsOverlayScaffold(title = "Bookmarks", onClose = onClose) {
        if (bookmarks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.BookmarkBorder, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text("No bookmarks yet", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Bookmark posts to find them again here.", color = colors.textSecondary, fontSize = 13.sp)
            }
        } else {
            LazyColumn {
                items(bookmarks, key = { "bookmark-${it.id}" }) { post ->
                    KaPostCell(
                        post = post,
                        viewModel = viewModel,
                        onOpenThread = { onOpenThread(post) },
                        onRepostTap = {},
                    )
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 68.dp))
                }
            }
        }
    }
}

/** Shared full-screen overlay chrome: back arrow + bold title over the app background. */
@Composable
private fun KaPostsOverlayScaffold(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = KaspaTeal)
                }
                Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            HorizontalDivider(color = colors.surfaceVariant)
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}

// MARK: - Toast overlays

@Composable
fun KaPostsToastOverlay(
    undoToast: KaPostsViewModel.UndoToast?,
    actionToast: KaPostsViewModel.ActionToast?,
    onUndo: () -> Unit,
    onViewTx: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = undoToast != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            undoToast?.let { toast ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.surfaceVariant, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    CountdownBadge(deadlineMs = toast.deadlineMs)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(toast.label, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Undo",
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onUndo() },
                    )
                }
            }
        }
        if (undoToast != null && actionToast != null) Spacer(modifier = Modifier.height(8.dp))
        AnimatedVisibility(
            visible = actionToast != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            actionToast?.let { toast ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.surfaceVariant, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Default.Favorite, null,
                        tint = Color(0xFF66BB6A),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(toast.message, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "View",
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onViewTx(toast.txId) },
                    )
                }
            }
        }
    }
}

// MARK: - Helpers

/** Compact relative timestamp: "now", "5m", "3h", "2d", else short date. */
fun relativePostTime(timestampMs: Long): String {
    val deltaSec = ((System.currentTimeMillis() - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        deltaSec < 60 -> "now"
        deltaSec < 3600 -> "${deltaSec / 60}m"
        deltaSec < 86_400 -> "${deltaSec / 3600}h"
        deltaSec < 7 * 86_400 -> "${deltaSec / 86_400}d"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(timestampMs))
    }
}

/** 1234 -> "1.2K" etc., X-style compact counters. */
fun formatEngagementCount(count: Int): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000f).removeSuffix(".0M").let { if (it.endsWith("M")) it else it + "M" }
    count >= 1_000 -> "%.1fK".format(count / 1_000f).removeSuffix(".0K").let { if (it.endsWith("K")) it else it + "K" }
    else -> count.toString()
}
