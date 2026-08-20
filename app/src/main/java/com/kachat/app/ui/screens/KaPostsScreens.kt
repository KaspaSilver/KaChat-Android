package com.kachat.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Payments
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.util.KaspaAddress
import com.kachat.app.viewmodels.ChatViewModel
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.models.KaPostDraft
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.KaPostsViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Cross-screen deep-link handoff: MainActivity (kachat://kapost/<txid>, universal links) and
 * notification taps set this; KaPostsScreen consumes it and opens the post's thread.
 */
object KaPostsDeepLink {
    val pendingPostTxId = MutableStateFlow<String?>(null)
}

/**
 * Properties for KaPosts' full-screen overlays.
 *
 * `decorFitsSystemWindows = false` is load-bearing, not cosmetic. The app targets SDK 36, where
 * edge-to-edge is enforced: the framework ignores a window's request to fit the system bars while
 * its DecorView still CONSUMES the insets on the way in. A Compose `Dialog` left on the default
 * (`true`) therefore ends up drawing under the status and navigation bars while every inset
 * modifier inside it resolves to zero - which is why the thread overlay's reply composer sat half
 * under the gesture-navigation bar. Turning it off stops the decor consuming, so the real insets
 * reach the content and [KaPostsOverlayInsets] below is the single source of truth. It is also the
 * documented prerequisite for the IME inset being reported inside a dialog at all.
 */
private val KaPostsFullScreenDialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)

/**
 * Status bar + navigation bar (gesture AND 3-button) + display cutout + the IME while it is up,
 * as ONE union. Applying them as a union rather than chaining `.imePadding()` after
 * `.navigationBarsPadding()` matters: the IME inset already spans the navigation bar, so the chain
 * double-counted the bottom and shoved the composer up by an extra nav-bar height whenever the
 * keyboard opened.
 */
private val KaPostsOverlayInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing

/**
 * Forces a Compose `Dialog`'s window to actually be full-screen. Call it as the first thing inside
 * the dialog's content.
 *
 * `usePlatformDefaultWidth = false` does NOT give the dialog a MATCH_PARENT window: `DialogLayout`
 * measures its content against `Configuration.screenHeightDp` and then calls
 * `window.setLayout(child.measuredWidth, child.measuredHeight)`, so the window is only ever as big
 * as the content it just measured. Under this app's enforced edge-to-edge (targetSdk 36) that
 * lands the window short of the content, and anything at the bottom of the content gets clipped
 * off the screen - which is what was cutting the thread overlay's reply composer in half. Asserting
 * MATCH_PARENT (plus ADJUST_RESIZE so the IME resizes the window, and no decor fitting so the real
 * insets reach the content) keeps these overlays whole.
 *
 * The thread overlay does not use this - it was moved out of a Dialog entirely, which is the
 * sturdier fix. These secondary overlays keep the Dialog because they carry no bottom-pinned
 * composer to lose.
 */
@Composable
private fun ForceFullScreenDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
        )
        // Deprecated from API 30, where setDecorFitsSystemWindows(false) below plus the IME inset
        // supersede it - still the only thing that resizes the window for the keyboard on this
        // app's minSdk 26..29 range, so both are set.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}

/**
 * Feed tab order, matching iOS's `FeedTab.allCases`: Following | Feed | Popular. The tab row and
 * the swipe pager both index into this one list, so they can never disagree about what page 0 is.
 */
private val KaPostsFeedTabs = listOf(
    KaPostsViewModel.FeedTab.FOLLOWING,
    KaPostsViewModel.FeedTab.FEED,
    KaPostsViewModel.FeedTab.POPULAR,
)

private fun KaPostsViewModel.FeedTab.label(): String = when (this) {
    KaPostsViewModel.FeedTab.FOLLOWING -> "Following"
    KaPostsViewModel.FeedTab.FEED -> "Feed"
    KaPostsViewModel.FeedTab.POPULAR -> "Popular"
}

// MARK: - Endless scrolling
//
// Every list in KaPosts pages the same way: a trigger that fires as the reader NEARS the end (not
// at the last row - by then the stall is already visible), and a footer that shows what the fetch
// is doing. The view model owns the "is one already in flight / have we hit the end" decision, so
// [EndlessScroll] can fire freely and [KaPostsViewModel.loadMore*] just no-ops when it shouldn't run.

/**
 * Calls [onLoadMore] whenever the last visible row comes within
 * [KaPostsViewModel.LOAD_MORE_THRESHOLD] of the end of [listState]'s list.
 *
 * Re-fires after an append too (the total grows, the check runs again), which is what fills a tall
 * screen when the KaChat-marker filter leaves a page with only a couple of visible rows.
 */
@Composable
private fun EndlessScroll(
    listState: LazyListState,
    key: Any? = Unit,
    onLoadMore: () -> Unit,
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, key) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 1 - KaPostsViewModel.LOAD_MORE_THRESHOLD) {
                    loadMore()
                }
            }
    }
}

/**
 * Bottom-of-list status row: a spinner while a page is being fetched, or a retry row when one
 * failed. A failure never clears what is already loaded - the reader keeps their list and their
 * place in it, and taps Retry to resume from the same cursor. Renders nothing once the surface has
 * reached the end, which is how the list stops asking.
 */
private fun LazyListScope.pagingFooter(
    state: KaPostsViewModel.PagingState,
    keySuffix: String,
    onRetry: () -> Unit,
) {
    if (!state.isLoadingMore && state.error == null) return
    item(key = "paging-footer-$keySuffix") {
        val colors = LocalAppColors.current
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.error != null) {
                Text("Couldn't load more.", color = colors.textSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Retry",
                    color = KaspaTeal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onRetry() },
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = KaspaTeal,
                )
            }
        }
    }
}

/** The live paging state for one surface, as Compose state. */
@Composable
private fun pagingStateOf(viewModel: KaPostsViewModel, key: String): KaPostsViewModel.PagingState {
    val all by viewModel.paging.collectAsState()
    return all[key] ?: KaPostsViewModel.PagingState()
}

// MARK: - Main screen

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun KaPostsScreen(
    navController: NavController,
    viewModel: KaPostsViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val selectedFeed by viewModel.selectedFeed.collectAsState()
    val visiblePosts by viewModel.visiblePosts.collectAsState()
    val visibleFollowingPosts by viewModel.visibleFollowingPosts.collectAsState()
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
    // The X-style repost menu's Quote choice can be raised from ANY cell (feed, thread,
    // profile, bookmarks) - the VM relays it here where the quote composer lives.
    val quoteRequest by viewModel.quoteRequest.collectAsState()
    LaunchedEffect(quoteRequest) {
        quoteRequest?.let {
            quoteTarget = it
            viewModel.consumeQuoteRequest()
        }
    }
    var showMyProfile by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var followListKind by remember { mutableStateOf<Boolean?>(null) } // true = followers
    // The profile whose follow list is open: null = my own list, non-null = another user's.
    var followListPubkey by remember { mutableStateOf<String?>(null) }
    // Quick-tip dialog target: (poster address, display name).
    var tipTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moderationKind by remember { mutableStateOf<Boolean?>(null) } // true = blocked
    var showBookmarks by remember { mutableStateOf(false) }
    var notFoundNotice by remember { mutableStateOf(false) }

    val posterProfile by viewModel.posterProfile.collectAsState()
    val deepLinkTxId by KaPostsDeepLink.pendingPostTxId.collectAsState()

    // Swipeable feed tabs. One LazyListState per tab, hoisted here rather than remembered inside
    // the pager page, so each feed keeps its scroll offset across swipes.
    val feedPagerState = rememberPagerState(
        initialPage = KaPostsFeedTabs.indexOf(selectedFeed).coerceAtLeast(0),
        pageCount = { KaPostsFeedTabs.size },
    )
    val followingListState = rememberLazyListState()
    val feedListState = rememberLazyListState()
    val popularListState = rememberLazyListState()
    val feedListStates = listOf(followingListState, feedListState, popularListState)
    // Two-way sync. Each direction no-ops once the other has caught up, so they can't ping-pong:
    // a swipe selects the tab (which then finds the pager already there), a tab tap animates the
    // pager across (which then re-selects the tab it is already on). settledPage rather than
    // currentPage, and the equality guard, keep selectFeed's network refresh from firing
    // mid-drag or redundantly on first composition.
    LaunchedEffect(feedPagerState) {
        snapshotFlow { feedPagerState.settledPage }.collect { page ->
            val tab = KaPostsFeedTabs[page]
            if (tab != viewModel.selectedFeed.value) viewModel.selectFeed(tab)
        }
    }
    LaunchedEffect(selectedFeed) {
        val target = KaPostsFeedTabs.indexOf(selectedFeed)
        if (target >= 0 && target != feedPagerState.currentPage) {
            feedPagerState.animateScrollToPage(target)
        }
    }

    // The thread overlay renders inside this screen's own composition (not a Dialog window), so it
    // is bounded by the shell's content area - which reserves room for the floating dock. KaPosts
    // is a tab route, so the dock is otherwise always drawn on top; ask the shell to drop it while
    // a thread is open, exactly as Cold Storage's full-screen scanner does. Dropping the dock also
    // collapses the shell's reserved bottom padding, which is what lets the overlay reach the
    // bottom of the screen.
    val threadOpen = threadStack.isNotEmpty()
    LaunchedEffect(threadOpen) { walletViewModel.setHideBottomBar(threadOpen) }
    DisposableEffect(Unit) { onDispose { walletViewModel.setHideBottomBar(false) } }

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

    LaunchedEffect(Unit) {
        // Entering KaPosts always lands on the MOST RECENT feed: reload page one and snap every
        // tab's list to the top (the saved scroll state would otherwise restore last visit's
        // position deep in older posts). Each snap runs in its OWN coroutine: scrollToItem is
        // a SUSPENDING call, and on a pager page that isn't composed yet it can park until
        // that page attaches - run sequentially, a parked hidden-tab snap blocked the visible
        // list's snap forever, which is why entry kept restoring the old position.
        viewModel.loadFeed()
        feedListStates.forEach { state -> launch { runCatching { state.scrollToItem(0) } } }
    }
    // The reload above swaps the list contents asynchronously - snap again once the fresh
    // feed actually lands, so a restored scroll offset can't survive the data swap.
    var snapOnNextFeed by remember { mutableStateOf(true) }
    LaunchedEffect(visiblePosts) {
        if (snapOnNextFeed && visiblePosts.isNotEmpty()) {
            snapOnNextFeed = false
            feedListStates.forEach { state -> launch { runCatching { state.scrollToItem(0) } } }
        }
    }
    LaunchedEffect(deepLinkTxId) {
        val txId = deepLinkTxId ?: return@LaunchedEffect
        KaPostsDeepLink.pendingPostTxId.value = null
        // "" is the tab-only sentinel (a notification with no target txid): landing on the
        // freshly-loaded feed is the whole job, nothing to deep-open.
        if (txId.isNotEmpty()) openShared(txId)
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
                    // Total balance in the header, same as every other main page (iOS
                    // KaPostsView's BalanceToolbarLabel) — trailing here since this
                    // screen's title is left-aligned rather than centered.
                    BalanceTopBarLabel(modifier = Modifier.padding(end = 4.dp))
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

                // Horizontal paging between the three feeds, synced both ways with the tab row
                // above (tap animates the page across; swipe moves the underline) - matching iOS's
                // page-style TabView. Draggable paging is safe here: unlike the chat list, post
                // cells carry no row-level horizontal gestures, and the thread/profile/menu
                // overlays are separate Dialog windows that never see this pager's drags.
                HorizontalPager(
                    state = feedPagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    key = { KaPostsFeedTabs[it] },
                ) { page ->
                    val tab = KaPostsFeedTabs[page]
                    val pageFeed = remember(tab, visiblePosts, visibleFollowingPosts) {
                        viewModel.feedFor(tab, visiblePosts, visibleFollowingPosts)
                    }
                    val feedPaging = pagingStateOf(
                        viewModel,
                        if (tab == KaPostsViewModel.FeedTab.FOLLOWING) {
                            KaPostsViewModel.PAGE_FOLLOWING_FEED
                        } else {
                            KaPostsViewModel.PAGE_GLOBAL_FEED
                        },
                    )
                    if (feedError != null && pageFeed.isEmpty()) {
                        FeedEmptyState(
                            title = "Couldn't load the feed",
                            body = feedError ?: "",
                            actionLabel = "Retry",
                            onAction = { viewModel.refresh() },
                        )
                    } else if (pageFeed.isEmpty() && !isLoading) {
                        FeedEmptyState(
                            title = if (tab == KaPostsViewModel.FeedTab.FOLLOWING) "Nothing here yet" else "No posts yet",
                            body = if (tab == KaPostsViewModel.FeedTab.FOLLOWING)
                                "Follow people from their posts and their content shows up here."
                            else
                                "Be the first to post something on the Kaspa network.",
                            actionLabel = null,
                            onAction = {},
                        )
                    } else {
                        // Endless scroll, per tab. Each tab keeps its own cursor in the view
                        // model, so a swipe away and back resumes exactly where it was.
                        EndlessScroll(listState = feedListStates[page], key = tab) {
                            viewModel.loadMoreFeed(tab)
                        }
                        LazyColumn(
                            // Hoisted per tab so each feed keeps its own scroll position when you
                            // swipe away and back (the pager disposes off-screen pages).
                            state = feedListStates[page],
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(pageFeed, key = { it.id }) { post ->
                                LaunchedEffect(post.posterAddress) {
                                    viewModel.ensureSenderProfileFetched(post.posterAddress)
                                }
                                // Thread-root probe: once per commented post, so "View thread"
                                // can appear on other people's threads too.
                                LaunchedEffect(post.remoteId) { viewModel.probeThreadRoot(post) }
                                KaPostCell(
                                    post = post,
                                    viewModel = viewModel,
                                    onOpenThread = { openThread(post) },
                                    onRepostTap = { repostHandler(post) },
                                    onOpenProfile = { viewModel.openPosterProfile(post.posterAddress, post.posterPubkey) },
                                    onOpenQuoted = { txId -> openShared(txId) },
                                    onViewEngagement = { engagementTarget = post },
                                    truncatesLongText = true,
                                    onTip = { tipTarget = post.posterAddress to viewModel.posterDisplayName(post.posterAddress) },
                                )
                                // X-style "View thread" under a thread root - opens the detail,
                                // where the full continuation renders as a connected section.
                                val threadRootFlags by viewModel.threadRootFlags.collectAsState()
                                val localThreadRoots by viewModel.localThreadRoots.collectAsState()
                                if (post.id in localThreadRoots ||
                                    (post.remoteId != null && threadRootFlags[post.remoteId] == true)
                                ) {
                                    Text(
                                        "⤷ View thread",
                                        color = KaspaTeal,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clickable { openThread(post) }
                                            .padding(start = 68.dp, top = 2.dp, bottom = 8.dp),
                                    )
                                }
                                HorizontalDivider(
                                    color = colors.surfaceVariant,
                                    modifier = Modifier.padding(start = 68.dp),
                                )
                            }
                            pagingFooter(feedPaging, keySuffix = "feed-$page") {
                                viewModel.loadMoreFeed(tab)
                            }
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
            viewModel = viewModel,
            onSubmitThread = { segments ->
                showComposer = false
                viewModel.scheduleThread(segments)
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
            viewModel = viewModel,
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

    // Thread stack - the topmost id renders; back pops. The overlay resolves the id against the
    // live post tree itself (it must recompose as replies land), so only the id is handed over.
    threadStack.lastOrNull()?.let { topId ->
        KaPostThreadOverlay(
            postId = topId,
            viewModel = viewModel,
            onClose = { threadStack = threadStack.dropLast(1) },
            onOpenNested = { nested -> openThread(nested) },
            onOpenProfile = { address, pubkey -> viewModel.openPosterProfile(address, pubkey) },
            onOpenShared = { txId -> openShared(txId) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
        )
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
            onOpenFollowList = { followListPubkey = null; followListKind = it },
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
            onOpenFollowList = { followListPubkey = profile.pubkey; followListKind = it },
            onTip = { tipTarget = it.posterAddress to viewModel.posterDisplayName(it.posterAddress) },
        )
    }

    tipTarget?.let { (tipAddress, tipName) ->
        KaPostTipDialog(
            address = tipAddress,
            displayName = tipName,
            onDismiss = { tipTarget = null },
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
            targetPubkey = followListPubkey,
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
        KaPostsFeedTabs.forEach { tab ->
            val label = tab.label()
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
    /**
     * In-thread cells pass this: the comment bubble then aims the thread's reply composer at THIS
     * post instead of opening its thread (desktop's `data-kaposts-reply-to`). Null everywhere else,
     * where the bubble keeps its "open the thread" meaning.
     */
    onReply: (() -> Unit)? = null,
    /** "Tip": opens the 1:1 chat with the poster in KAS-send mode. Hidden on your own posts. */
    onTip: (() -> Unit)? = null,
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
                // ClickableText (not Text): tapping an @mention resolves the KNS domain and
                // opens that user's profile; taps elsewhere in the body do nothing.
                val postAnnotated = remember(post.text) { annotatedPostText(post.text) }
                androidx.compose.foundation.text.ClickableText(
                    text = postAnnotated,
                    style = TextStyle(color = colors.textPrimary, fontSize = 15.sp, lineHeight = 20.sp),
                    maxLines = if (foldText) 8 else Int.MAX_VALUE,
                    overflow = if (foldText) TextOverflow.Ellipsis else TextOverflow.Clip,
                    onClick = { offset ->
                        postAnnotated.getStringAnnotations(MENTION_ANNOTATION_TAG, offset, offset)
                            .firstOrNull()
                            ?.let { viewModel.openMentionProfile(it.item) }
                    },
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
                    onComment = onReply ?: onOpenThread,
                    onRepost = onRepostTap,
                    onLike = { viewModel.toggleLike(post) },
                    onDislike = { viewModel.toggleDislike(post) },
                    onBookmark = { viewModel.toggleBookmark(post) },
                    onCancelCountdown = { viewModel.cancelUndoable(it) },
                    onTip = if (!isMine) onTip else null,
                    // X-style anchored repost menu; quote routes through the VM's quoteRequest
                    // flow so the main screen's composer opens from any cell.
                    onRepostConfirm = { viewModel.scheduleRepost(post) },
                    onQuote = { viewModel.requestQuote(post) },
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
    onTip: (() -> Unit)? = null,
    // X-style repost menu: when BOTH are set (and the post is on-chain), tapping repost opens
    // a compact anchored two-row menu (Repost / Quote) instead of the old dialog.
    onRepostConfirm: (() -> Unit)? = null,
    onQuote: (() -> Unit)? = null,
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
        Box {
            var repostMenuOpen by remember { mutableStateOf(false) }
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
                onTap = {
                    // X-style: a compact menu floating over the tapped button.
                    if (onRepostConfirm != null && onQuote != null && post.remoteId != null) {
                        repostMenuOpen = true
                    } else {
                        onRepost()
                    }
                },
                onCancel = onCancelCountdown,
            )
            DropdownMenu(
                expanded = repostMenuOpen,
                onDismissRequest = { repostMenuOpen = false },
                modifier = Modifier.background(colors.surface),
            ) {
                DropdownMenuItem(
                    text = { Text("Repost", color = colors.textPrimary, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.Repeat, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        repostMenuOpen = false
                        onRepostConfirm?.invoke()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Quote", color = colors.textPrimary, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        repostMenuOpen = false
                        onQuote?.invoke()
                    },
                )
            }
        }
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
        if (onTip != null) {
            Spacer(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTip() }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                // The real Kaspa logo, matching iOS's Tip button.
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Tip", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
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
    /** Enables @mention autocomplete (chips of 1:1 KNS-domain contacts) when provided. */
    viewModel: KaPostsViewModel? = null,
    /** Enables X-style thread posting (+ stacks segments; Post All submits the chain). */
    onSubmitThread: ((List<String>) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    var text by remember { mutableStateOf("") }
    var threadSegments by remember { mutableStateOf(listOf<String>()) }
    val limit = KaPostDraft.POST_CHARACTER_LIMIT
    val totalSegments = threadSegments.size + (if (text.isNotBlank()) 1 else 0)
    val canPost = totalSegments > 0 && text.length <= limit
    val threadingEnabled = onSubmitThread != null && quoted == null

    // Warm the KNS caches so typing @ has domains to offer.
    LaunchedEffect(Unit) { viewModel?.prefetchMentionCandidates() }
    // The @token being typed at the END of the text ("" right after "@"), or null.
    val mentionQuery = remember(text) {
        Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]*)$", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(2)?.lowercase()
    }
    // Anyone-with-a-KNS-domain mentions: debounce-resolve the typed query live.
    var resolvedAnyDomain by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mentionQuery) {
        resolvedAnyDomain = null
        val query = mentionQuery ?: return@LaunchedEffect
        if (query.length < 2 || viewModel == null) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        resolvedAnyDomain = viewModel.resolveMentionQuery(query)
    }
    val mentionSuggestions = remember(text, resolvedAnyDomain) {
        val query = mentionQuery
        if (query == null || viewModel == null) emptyList()
        else {
            val contacts = viewModel.mentionCandidates()
                .map { it.first }
                .filter { query.isEmpty() || it.startsWith(query) }
                .sorted()
                .take(6)
            val extra = resolvedAnyDomain
            if (extra != null && extra !in contacts && (query.isEmpty() || extra.startsWith(query))) {
                contacts + extra
            } else contacts
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = KaPostsFullScreenDialogProperties,
    ) {
        ForceFullScreenDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(KaPostsOverlayInsets),
        ) {
            // Header, matching iOS/desktop's composer card: X in a rounded square, bold title,
            // character meter, teal capsule Post button.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .clickable { onDismiss() }
                        .padding(10.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (threadSegments.isNotEmpty() && quoted == null) "New Thread" else title,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f),
                )
                KaPostCharacterMeter(count = text.length)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    if (totalSegments > 1) "Post All ($totalSegments)" else "Post",
                    color = if (canPost) Color.Black else colors.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (canPost) KaspaTeal else colors.surface)
                        .clickable(enabled = canPost) {
                            val trimmed = text.trim()
                            val segments = threadSegments + (if (trimmed.isNotEmpty()) listOf(trimmed) else emptyList())
                            if (segments.isEmpty()) return@clickable
                            if (segments.size > 1 && onSubmitThread != null) onSubmitThread(segments)
                            else onSubmit(segments.first())
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            // Already-stacked thread segments (X-style), numbered and removable.
            if (threadSegments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    threadSegments.forEachIndexed { index, segment ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surface)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "${index + 1}",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(KaspaTeal.copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                segment,
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "×",
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .clickable {
                                        threadSegments = threadSegments.filterIndexed { i, _ -> i != index }
                                    }
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.surfaceVariant)
            }
            // @mention autocomplete: a SCROLLABLE vertical list of the KNS domains of everyone
            // you've chatted with (plus a live-resolved any-KNS match), iOS/group-chat style.
            // Above the editor so the keyboard can never hide it.
            if (mentionSuggestions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .widthIn(max = 280.dp)
                        .heightIn(max = 168.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .verticalScroll(rememberScrollState()),
                ) {
                    mentionSuggestions.forEachIndexed { index, domain ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    text = text.replace(Regex("@[a-z0-9-]*$", RegexOption.IGNORE_CASE), "@$domain ")
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text("@", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(domain, color = colors.textPrimary, fontSize = 14.sp)
                        }
                        if (index != mentionSuggestions.lastIndex) {
                            HorizontalDivider(color = colors.surfaceVariant)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Bordered editor card with the X-style + floating in its corner: tapping + stacks
            // the current text as a thread segment and clears the editor for the next post.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, colors.textSecondary.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= limit) text = it },
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(KaspaTeal),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                if (threadSegments.isEmpty()) "What's happening on Kaspa?" else "Add another post",
                                color = colors.textSecondary,
                                fontSize = 16.sp,
                            )
                        }
                        inner()
                    },
                )
                if (threadingEnabled && text.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(KaspaTeal.copy(alpha = 0.15f))
                            .clickable {
                                threadSegments = threadSegments + text.trim()
                                text = ""
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("＋", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
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

/**
 * Rendered as a full-screen overlay INSIDE the KaPosts composition, deliberately not as a
 * `Dialog`.
 *
 * A Compose `Dialog` with `usePlatformDefaultWidth = false` never gets a MATCH_PARENT window:
 * `DialogLayout` measures its content against `Configuration.screenHeightDp` and then calls
 * `window.setLayout(child.measuredWidth, child.measuredHeight)` on every layout pass. Under this
 * app's enforced edge-to-edge (targetSdk 36) the resulting window ends up shorter than the content
 * being laid out inside it, so the bottom of that content - the reply composer - was clipped off
 * the bottom of the screen, and no amount of inset padding could fix it because the window itself
 * was the wrong size. In the ordinary composition the overlay is measured by the same layout path
 * as every other screen in the app, which lays out correctly.
 */
@Composable
fun KaPostThreadOverlay(
    postId: String,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    onOpenNested: (KaPostDraft) -> Unit,
    onOpenProfile: (String, String?) -> Unit,
    onOpenShared: (String) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
) {
    val colors = LocalAppColors.current
    // Resolving against the collected tree (rather than taking a KaPostDraft parameter) is what
    // makes the thread live: fetched replies, inline-expanded sub-threads and optimistic replies
    // all land inside the view model's post lists, and this is what re-reads them.
    val tree by viewModel.postTree.collectAsState()
    val post = remember(postId, tree) { viewModel.findPost(postId) }
    if (post == null) {
        // The thread's post vanished (feed refresh dropped it) - pop this level.
        LaunchedEffect(postId) { onClose() }
        return
    }
    var replyText by remember(postId) { mutableStateOf("") }
    /** Which post in this thread the composer targets; null = the thread root. */
    var replyTargetId by remember(postId) { mutableStateOf<String?>(null) }
    // Zero-balance funding gate — tapping the reply composer while the chatting balance is a
    // confirmed 0 KAS opens the shared funding card instead of the reply field/keyboard.
    val fundingGate = rememberZeroBalanceFundingGate()
    var showFundingGate by remember { mutableStateOf(false) }
    var expandedIds by remember(postId) { mutableStateOf(setOf<String>()) }
    val muted by viewModel.muted.collectAsState()
    val blocked by viewModel.blocked.collectAsState()
    val hidden = muted + blocked
    // The author's own continuation renders as a connected Thread section under the root;
    // its segments are excluded from the comment list (segment 2 IS a direct reply).
    val threadChains by viewModel.threadChains.collectAsState()
    val threadChain = threadChains[post.id].orEmpty()
    val chainRemoteIds = remember(threadChain) { threadChain.mapNotNull { it.remoteId }.toSet() }
    val visibleComments = post.comments.filter {
        it.posterAddress !in hidden && (it.remoteId == null || it.remoteId !in chainRemoteIds)
    }
    // Falls back to the root whenever the targeted comment is gone (a refresh replaced it).
    val replyTarget = remember(replyTargetId, tree, post) {
        replyTargetId?.let { viewModel.findPost(it) } ?: post
    }
    val replyingToComment = replyTarget.id != post.id

    LaunchedEffect(post.remoteId) {
        viewModel.loadReplies(post)
        viewModel.loadSelfThreadChain(post)
    }

    // Endless scroll through the thread's replies. Keyed on the root's txid, so pushing a nested
    // comment as a new thread root starts a fresh surface rather than inheriting this one's cursor.
    val threadListState = rememberLazyListState()
    val threadPaging = pagingStateOf(
        viewModel,
        post.remoteId?.let { KaPostsViewModel.pageThread(it) } ?: "thread:none",
    )
    EndlessScroll(listState = threadListState, key = post.remoteId) {
        viewModel.loadMoreReplies(post)
    }

    // System back closes this level of the thread, matching the Dialog's dismiss behaviour and the
    // Back arrow in the header. Nested pushes each get their own overlay instance, so back walks
    // the thread stack down one level at a time.
    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            // Opaque AND gesture-claiming: the feed pager and the post cells behind this overlay
            // must never see a touch that landed on the thread. Children are dispatched first in
            // the Main pass, so the list, buttons and the text field keep working normally; this
            // only swallows whatever they left unclaimed - notably horizontal drags, which the
            // pager would otherwise read as a tab swipe.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { if (!it.isConsumed) it.consume() }
                    }
                }
            },
    ) {
        // Header (wrap) / thread (weight 1f, the only scrolling region) / composer (wrap, pinned
        // to the bottom). The composer is the LAST non-weighted child, so it always gets its
        // intrinsic height and the list absorbs whatever is left - including the shrink when the
        // keyboard opens, which is what keeps the composer riding above the IME instead of being
        // pushed off-screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(KaPostsOverlayInsets),
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
            LazyColumn(state = threadListState, modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        // In-thread the comment bubble means "reply to this", not "open this".
                        onReply = { replyTargetId = null },
                    )
                    HorizontalDivider(color = colors.surfaceVariant)
                }
                // X-style thread reading: the author's own continuation, connected and ordered.
                if (threadChain.isNotEmpty()) {
                    item(key = "thread-chain-header") {
                        Text(
                            "Thread · ${threadChain.size + 1} posts",
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(threadChain, key = { "chain-${it.id}" }) { segment ->
                        LaunchedEffect(segment.posterAddress) {
                            viewModel.ensureSenderProfileFetched(segment.posterAddress)
                        }
                        Row(modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(KaspaTeal.copy(alpha = 0.35f)),
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                KaPostCell(
                                    post = segment,
                                    viewModel = viewModel,
                                    onOpenThread = { onOpenNested(segment) },
                                    onRepostTap = { onRepostTap(segment) },
                                    onOpenProfile = { onOpenProfile(segment.posterAddress, segment.posterPubkey) },
                                    onOpenQuoted = onOpenShared,
                                    onViewEngagement = { onViewEngagement(segment) },
                                )
                            }
                        }
                    }
                    item(key = "thread-chain-divider") {
                        HorizontalDivider(color = colors.surfaceVariant)
                    }
                }
                if (visibleComments.isEmpty()) {
                    // Otherwise the space between the post and the pinned composer is just a
                    // large black void (it filled most of the screen in the bug report). Same
                    // copy as iOS's thread view.
                    item(key = "no-comments") {
                        Text(
                            "No comments yet - be the first to reply.",
                            color = colors.textSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
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
                        onReplyTo = { target -> replyTargetId = target.id },
                    )
                    HorizontalDivider(
                        color = colors.surfaceVariant,
                        modifier = Modifier.padding(start = 88.dp),
                    )
                }
                pagingFooter(threadPaging, keySuffix = "thread") {
                    viewModel.loadMoreReplies(post)
                }
            }
            HorizontalDivider(color = colors.surfaceVariant)
            // While the funding gate is active the reply row renders dimmed and any tap on it
            // opens the funding card instead of focusing the field — same "no composer until
            // funded" rule as the New Post FAB above.
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (fundingGate.active) 0.35f else 1f),
                ) {
                    // "Replying to <name> x" - the composer targets a comment rather than the
                    // thread root (desktop's reply-context chip). Clearing it aims at the root.
                    if (replyingToComment) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 12.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Replying to ${viewModel.posterDisplayName(replyTarget.posterAddress)}",
                                color = KaspaTeal,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Reply to the original post instead",
                                tint = colors.textSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { replyTargetId = null },
                            )
                        }
                    }
                    // @mention autocomplete for COMMENTS - identical machinery to the post
                    // composer: KNS domains of everyone you've chatted with, plus a live
                    // any-KNS resolve of the typed query. Shown above the input so the
                    // keyboard can never hide it.
                    LaunchedEffect(Unit) { viewModel.prefetchMentionCandidates() }
                    val replyMentionQuery = remember(replyText) {
                        Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]*)$", RegexOption.IGNORE_CASE)
                            .find(replyText)?.groupValues?.get(2)?.lowercase()
                    }
                    var replyResolvedAnyDomain by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(replyMentionQuery) {
                        replyResolvedAnyDomain = null
                        val query = replyMentionQuery ?: return@LaunchedEffect
                        if (query.length < 2) return@LaunchedEffect
                        kotlinx.coroutines.delay(400)
                        replyResolvedAnyDomain = viewModel.resolveMentionQuery(query)
                    }
                    val replyMentionSuggestions = remember(replyText, replyResolvedAnyDomain) {
                        val query = replyMentionQuery
                        if (query == null) emptyList()
                        else {
                            val contacts = viewModel.mentionCandidates()
                                .map { it.first }
                                .filter { query.isEmpty() || it.startsWith(query) }
                                .sorted()
                                .take(6)
                            val extra = replyResolvedAnyDomain
                            if (extra != null && extra !in contacts && (query.isEmpty() || extra.startsWith(query))) {
                                contacts + extra
                            } else contacts
                        }
                    }
                    if (replyMentionSuggestions.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .widthIn(max = 280.dp)
                                .heightIn(max = 168.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surface)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            replyMentionSuggestions.forEachIndexed { index, domain ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            replyText = replyText.replace(Regex("@[a-z0-9-]*$", RegexOption.IGNORE_CASE), "@$domain ")
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                ) {
                                    Text("@", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(domain, color = colors.textPrimary, fontSize = 14.sp)
                                }
                                if (index != replyMentionSuggestions.lastIndex) {
                                    HorizontalDivider(color = colors.surfaceVariant)
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                    Text(
                                        if (replyingToComment) "Post your reply to this comment" else "Post your reply",
                                        color = colors.textSecondary,
                                        fontSize = 15.sp,
                                    )
                                }
                                inner()
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        KaPostCharacterMeter(count = replyText.length)
                        TextButton(
                            onClick = {
                                // Replies nest against their IMMEDIATE parent (postId + mention =
                                // that comment and its author), which is what the indexer keys
                                // get-replies on - same rule as iOS/desktop.
                                val target = replyTarget
                                viewModel.submitReply(target, replyText.trim())
                                // Reveal the new reply straight away: a comment's children only
                                // render while it's expanded. Pull its existing chain in too, so
                                // the expansion isn't just our own reply on its own.
                                if (target.id != post.id && target.id !in expandedIds) {
                                    viewModel.expandReplies(target)
                                    expandedIds = expandedIds + target.id
                                }
                                replyText = ""
                                replyTargetId = null
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
 * (connector line at the leading edge), recursively. The comment bubble replies to THIS comment
 * (the composer retargets); tapping the comment body pushes it as a new thread root for full depth.
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
    onReplyTo: (KaPostDraft) -> Unit,
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
            onReply = { onReplyTo(comment) },
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
                // An inline expansion is not its own scroll container (it is drawn inside ONE
                // LazyColumn row), so there is no scroll position to derive a trigger from -
                // deeper pages of a sub-thread load on tap instead.
                val nestedPaging = pagingStateOf(
                    viewModel,
                    comment.remoteId?.let { KaPostsViewModel.pageThread(it) } ?: "thread:none",
                )
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
                        onReplyTo = onReplyTo,
                    )
                }
                if (nestedPaging.isLoadingMore) {
                    Row(modifier = Modifier.padding(start = 56.dp, top = 2.dp, bottom = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = KaspaTeal)
                    }
                } else if (nestedPaging.hasMore || nestedPaging.error != null) {
                    Text(
                        text = if (nestedPaging.error != null) "Couldn't load more - retry" else "Show more replies",
                        color = KaspaTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { viewModel.loadMoreReplies(comment) }
                            .padding(start = 56.dp, top = 2.dp, bottom = 8.dp),
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
    /** Quick-tip dialog opener; falls back to the chat payment screen when null. */
    onTip: ((KaPostDraft) -> Unit)? = null,
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
    val listState = rememberLazyListState()
    // Posts and Replies are separate paging surfaces (separate endpoints, separate cursors), so
    // the footer and the trigger both follow whichever tab is open.
    val profilePubkey = remember(isMine, pubkey, posterProfile?.pubkey) { viewModel.profilePubkey(isMine) }
    val profilePaging = pagingStateOf(
        viewModel,
        profilePubkey?.let { KaPostsViewModel.pageProfile(it, isMine, selectedTab == 1) } ?: "profile:none",
    )

    LaunchedEffect(address) { viewModel.ensureSenderProfileFetched(address) }
    EndlessScroll(listState = listState, key = selectedTab to profilePubkey) {
        viewModel.loadMoreProfile(isMine, replies = selectedTab == 1)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = KaPostsFullScreenDialogProperties,
    ) {
        // Same window-sizing fix as the other overlays. This one deliberately keeps its content
        // edge-to-edge at the top (the KNS banner runs under the status bar), so it takes the
        // navigation-bar inset on the list's bottom instead of padding the whole Column.
        ForceFullScreenDialogWindow()
        Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            ) {
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
                        LaunchedEffect(post.posterAddress) {
                            viewModel.ensureSenderProfileFetched(post.posterAddress)
                        }
                        KaPostCell(
                            post = post,
                            viewModel = viewModel,
                            onOpenThread = { onOpenThread(post) },
                            onRepostTap = { onRepostTap(post) },
                            onOpenQuoted = onOpenQuoted,
                            onViewEngagement = { onViewEngagement(post) },
                            onTip = onTip?.let { open -> { open(post) } }
                                ?: { navController.navigate("chat/${post.posterAddress}?paymentMode=true") },
                        )
                        HorizontalDivider(
                            color = colors.surfaceVariant,
                            modifier = Modifier.padding(start = 68.dp),
                        )
                    }
                    pagingFooter(profilePaging, keySuffix = "profile-$selectedTab") {
                        viewModel.loadMoreProfile(isMine, replies = selectedTab == 1)
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

    val listState = rememberLazyListState()
    val paging = pagingStateOf(viewModel, KaPostsViewModel.PAGE_NOTIFICATIONS)

    LaunchedEffect(Unit) { viewModel.loadNotifications() }
    EndlessScroll(listState = listState) { viewModel.loadMoreNotifications() }

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
            LazyColumn(state = listState) {
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
                pagingFooter(paging, keySuffix = "notifications") { viewModel.loadMoreNotifications() }
            }
        }
    }
}

/**
 * Quick tip: Send-Kaspa-style dialog matching iOS's KaPostTipSheet - fixed recipient with the
 * pool-destination indicator, amount with the funding source's Available, Normal/Fast/Priority
 * tiers (a rate multiplier consumed by the next send). The send routes through
 * ChatViewModel.sendPayment, so destination + funding follow the chat payment privacy rules
 * exactly, and the payment bubble lands in the 1:1 conversation.
 */
@Composable
fun KaPostTipDialog(
    address: String,
    displayName: String,
    onDismiss: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    var amountText by remember { mutableStateOf("") }
    var feeTier by remember { mutableStateOf(1L) }
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val paysViaPool by chatViewModel.paysToFreshPoolAddress.collectAsState()
    val estimatedFee by chatViewModel.estimatedFeeSompi.collectAsState()
    val spendingUtxos by chatViewModel.spendingUtxos.collectAsState()
    val availableKas = remember(spendingUtxos) {
        spendingUtxos.sumOf { it.utxoEntry.amount } / 100_000_000.0
    }

    LaunchedEffect(address) {
        // Deliberately does NOT create a contact here: opening the tip dialog and cancelling
        // must leave no trace in the Chats list. The contact is created in the Send Tip
        // click, right before the payment goes out.
        chatViewModel.refreshFreshPoolIndicator(address)
        chatViewModel.refreshSpendingUtxos()
        chatViewModel.setFeeRateOverride(null)
    }
    // The amount drives the live fee preview through the same estimator the chat composer uses.
    LaunchedEffect(amountText) { chatViewModel.setPaymentAmount(amountText) }

    AlertDialog(
        onDismissRequest = {
            chatViewModel.setFeeRateOverride(null)
            chatViewModel.setPaymentAmount("")
            onDismiss()
        },
        containerColor = colors.surface,
        title = { Text("Tip $displayName", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            // Sectioned like iOS's KaPostTipSheet Form: recipient card + destination line,
            // amount with the Kaspa logo + Available footer, fee tiers + Network Fee row.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        displayName,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        KaspaAddress.shortDisplay(address),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                    )
                }
                // Which privacy scenario this tip will hit (same signal as the chat composer).
                Text(
                    if (paysViaPool) "🔒 Goes to a fresh private address they shared"
                    else "🌐 Goes to their public chatting address",
                    color = if (paysViaPool) Color(0xFF35C48D) else colors.textSecondary,
                    fontSize = 12.5.sp,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; errorText = null },
                    label = { Text("Amount (KAS)") },
                    singleLine = true,
                    leadingIcon = {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Available: ${"%.8f".format(availableKas).trimEnd('0').trimEnd('.')} KAS",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("Normal" to 1L, "Fast" to 2L, "Priority" to 5L).forEach { (label, mult) ->
                        val selected = feeTier == mult
                        Text(
                            label,
                            color = if (selected) Color.Black else colors.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) KaspaTeal else colors.surfaceVariant)
                                .clickable {
                                    feeTier = mult
                                    chatViewModel.setFeeTierMultiplier(mult)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Network Fee", color = colors.textPrimary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        // estimatedFeeSompi already reflects the tier (the estimator combines
                        // the fee-rate override) - display it as-is, never re-multiply.
                        estimatedFee?.let { fee ->
                            "${"%.8f".format(fee / 100_000_000.0).trimEnd('0').trimEnd('.')} KAS"
                        } ?: "—",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                    )
                }
                errorText?.let {
                    Text(it, color = Color(0xFFE57373), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSending && (amountText.toDoubleOrNull() ?: 0.0) > 0.0,
                onClick = {
                    isSending = true
                    errorText = null
                    // The chat with the poster is created HERE, on an actual send - not when
                    // the dialog opened - so a cancelled tip never leaves an orphan chat.
                    chatViewModel.addContact(address, displayName.takeIf { it.isNotBlank() && !it.startsWith("kaspa:") })
                    // Re-apply the tier right before the send (sendPayment consumes the override).
                    chatViewModel.setFeeTierMultiplier(feeTier)
                    chatViewModel.sendPayment(address, amountText.trim()) { ok, error ->
                        if (ok) {
                            chatViewModel.setPaymentAmount("")
                            onDismiss()
                        } else {
                            isSending = false
                            errorText = error ?: "Tip failed."
                        }
                    }
                },
            ) {
                Text(if (isSending) "Sending…" else "Send Tip", color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                chatViewModel.setFeeRateOverride(null)
                chatViewModel.setPaymentAmount("")
                onDismiss()
            }) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

/** Annotation tag carried by @mention ranges - ClickableText resolves it to a profile. */
const val MENTION_ANNOTATION_TAG = "mention"

/** Post text with @mention tokens tinted teal AND annotated for tap-to-profile. */
private fun annotatedPostText(text: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        for (match in KaPostsViewModel.MENTION_TOKEN_REGEX.findAll(text)) {
            val domain = match.groups[2] ?: continue
            val start = domain.range.first - 1 // include the '@'
            if (start < 0) continue
            val end = domain.range.last + 1
            addStyle(
                androidx.compose.ui.text.SpanStyle(color = KaspaTeal, fontWeight = FontWeight.SemiBold),
                start,
                end,
            )
            addStringAnnotation(
                MENTION_ANNOTATION_TAG,
                domain.value.lowercase().removeSuffix(".kas"),
                start,
                end,
            )
        }
    }

private fun notificationActionText(kind: KaPostsViewModel.NotificationItem.Kind): String = when (kind) {
    KaPostsViewModel.NotificationItem.Kind.LIKE -> "liked your post"
    KaPostsViewModel.NotificationItem.Kind.DISLIKE -> "disliked your post"
    KaPostsViewModel.NotificationItem.Kind.REPLY -> "replied to your post"
    KaPostsViewModel.NotificationItem.Kind.QUOTE -> "quoted your post"
    KaPostsViewModel.NotificationItem.Kind.REPOST -> "reposted your post"
    KaPostsViewModel.NotificationItem.Kind.FOLLOW -> "followed you"
    KaPostsViewModel.NotificationItem.Kind.MENTION -> "mentioned you in a post"
    KaPostsViewModel.NotificationItem.Kind.OTHER -> "interacted with your post"
}

// MARK: - Follow list overlay

@Composable
fun KaPostsFollowListOverlay(
    followers: Boolean,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    targetPubkey: String? = null,
) {
    val colors = LocalAppColors.current
    val following by viewModel.following.collectAsState()
    val senderProfiles by viewModel.senderProfiles.collectAsState()
    val entries by viewModel.followEntries.collectAsState()
    val listState = rememberLazyListState()
    val paging = pagingStateOf(viewModel, KaPostsViewModel.pageFollowList(followers))
    val myAddress = viewModel.myAddress()

    LaunchedEffect(followers, targetPubkey) { viewModel.loadFollowList(followers, targetPubkey) }
    EndlessScroll(listState = listState, key = followers) { viewModel.loadMoreFollowList(followers, targetPubkey) }

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
            LazyColumn(state = listState) {
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
                        // No follow control for yourself (you can appear on another user's list).
                        if (entry.address != myAddress) {
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
                    }
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                }
                pagingFooter(paging, keySuffix = "follows") { viewModel.loadMoreFollowList(followers) }
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
    val lists by viewModel.engagementLists.collectAsState()
    val loaded by viewModel.engagementLoaded.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val paging = pagingStateOf(
        viewModel,
        post.remoteId?.let { KaPostsViewModel.pageEngagement(it) } ?: "engagement:none",
    )

    LaunchedEffect(post.remoteId) { viewModel.loadEngagement(post) }
    // The stream carries all four kinds at once, so the loop targets the OPEN tab: switching tabs
    // re-arms the trigger against the kind the reader is now looking at.
    EndlessScroll(listState = listState, key = post.remoteId to selectedTab) {
        viewModel.loadMoreEngagement(post, selectedTab)
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
                    LazyColumn(state = listState) {
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
                        pagingFooter(paging, keySuffix = "engagement") {
                            viewModel.loadMoreEngagement(post, selectedTab)
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
        properties = KaPostsFullScreenDialogProperties,
    ) {
        ForceFullScreenDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(KaPostsOverlayInsets),
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
