package com.kachat.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PersonAddAlt1
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
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Cross-screen deep-link handoff: MainActivity (kachat://kapost/<txid>, universal links) and
 * notification taps set this; KaPostsScreen consumes it and opens the post's thread.
 */
object KaPostsDeepLink {
    val pendingPostTxId = MutableStateFlow<String?>(null)

    /** For reply notifications: the reply's own txid, so the opened PARENT thread (which is
     *  what [pendingPostTxId] carries) can scroll to the new comment. */
    val pendingFocusReplyTxId = MutableStateFlow<String?>(null)
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
    // LaunchedEffect, NOT SideEffect: these window attributes only need setting once per
    // dialog. As a SideEffect this re-ran after EVERY recomposition — in the composer
    // dialogs that meant a WindowManager relayout + soft-input re-assert per keystroke,
    // which is real typing lag under an attached IME.
    LaunchedEffect(Unit) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
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

// MARK: - Per-key state selection
//
// The KaPosts view model keeps per-address / per-post data in whole-map StateFlows. Collecting
// one of those maps INSIDE a list cell subscribes every visible cell to every entry: one avatar
// or probe result arriving re-ran the whole viewport - and profile fetches fire per row as it
// scrolls in, so the invalidation landed exactly mid-scroll (the primary feed jank cause; the
// iOS twin had the identical bug). These helpers subscribe a composable to ONE derived slice,
// so a cell recomposes only when ITS value actually changes.

/** Compose state for one derived [selector] slice of [this]. [keys] must cover the selector's captures. */
@Composable
private fun <T, R> StateFlow<T>.collectSelectedAsState(vararg keys: Any?, selector: (T) -> R): State<R> {
    val sliced = remember(this, *keys) { map(selector).distinctUntilChanged() }
    return sliced.collectAsState(initial = selector(value))
}

/**
 * The poster display chain (contact alias > KNS domain > shortened address) as LIVE per-address
 * state: recomposes its reader when THIS address's alias or KNS name lands, and only then.
 * Mirrors [KaPostsViewModel.posterDisplayName], which stays the one-shot non-reactive variant.
 */
@Composable
private fun posterDisplayNameState(viewModel: KaPostsViewModel, address: String): String {
    val alias by viewModel.contactAliases.collectSelectedAsState(address) { it[address] }
    val kns by viewModel.senderKnsNames.collectSelectedAsState(address) { it[address] }
    return remember(alias, kns, address) {
        alias?.takeIf { it.isNotBlank() }?.let { viewModel.strippingKasSuffix(it) }
            ?: kns?.takeIf { it.isNotBlank() }?.let { viewModel.strippingKasSuffix(it) }
            ?: if (address.isEmpty()) "Unknown" else address.takeLast(10)
    }
}

/** The live paging state for one surface, as Compose state - sliced per key, so a page load on
 *  one surface no longer recomposes every other open surface (feed tabs, thread, profile tabs). */
@Composable
private fun pagingStateOf(viewModel: KaPostsViewModel, key: String): KaPostsViewModel.PagingState {
    val state by viewModel.paging.collectSelectedAsState(key) { it[key] ?: KaPostsViewModel.PagingState() }
    return state
}

// MARK: - Main screen

/**
 * Where back should land after closing a thread that was opened FROM a profile overlay.
 * The profile Dialog has to close before the in-composition thread can show (see the
 * close-then-open comments at the overlay call sites), so without remembering it, backing
 * out of that thread dumped the user on the main feed instead of the profile they came from.
 */
private sealed interface KaPostsProfileReturn {
    /** My own profile (the side menu's Profile entry). */
    object Mine : KaPostsProfileReturn

    /** Another poster's profile. */
    data class Poster(val address: String, val pubkey: String?) : KaPostsProfileReturn
}

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
    // Toasts and the connection dot deliberately collect inside their own composables
    // (KaPostsToastLayer / ConnectionDotButton): every toast tick and node-health color change
    // used to recompose this ENTIRE screen body, feed pager included, mid-scroll.

    var showSideMenu by remember { mutableStateOf(false) }
    var showComposer by remember { mutableStateOf(false) }
    // Zero-balance funding gate — tapping "New post" while the chatting balance is a confirmed
    // 0 KAS opens the shared funding card as a dialog instead of the post composer (replies get
    // the same treatment inside KaPostThreadOverlay). See GiftClaimUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()
    var showFundingGate by remember { mutableStateOf(false) }
    /** Thread stack: each entry is a post's LOCAL id; tapping nested comments pushes deeper. */
    var threadStack by remember { mutableStateOf(listOf<String>()) }
    /** One-shot: remoteId of a reply the NEXT opened thread should scroll to (reply
     *  notifications open the parent's thread and land the reader on the new comment). */
    var threadFocusReplyId by remember { mutableStateOf<String?>(null) }
    // Keyed by the thread-stack INDEX of the entry that was opened from a profile, so nested
    // threads pushed on top pop normally and only closing that exact entry re-opens the
    // profile it came from (a thread opened from the feed never restores anything).
    var profileReturns by remember { mutableStateOf(mapOf<Int, KaPostsProfileReturn>()) }
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
    val feedListStates = remember(followingListState, feedListState, popularListState) {
        listOf(followingListState, feedListState, popularListState)
    }
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

    /** Pops the topmost thread; if that entry was opened from a profile, re-opens the profile. */
    fun closeTopThread() {
        val closingIndex = threadStack.size - 1
        if (closingIndex < 0) return
        threadStack = threadStack.dropLast(1)
        threadFocusReplyId = null // never let a stale focus scroll some later thread
        val returnTo = profileReturns[closingIndex] ?: return
        profileReturns = profileReturns - closingIndex
        when (returnTo) {
            KaPostsProfileReturn.Mine -> {
                showMyProfile = true
                viewModel.loadMyProfile()
            }
            is KaPostsProfileReturn.Poster -> viewModel.openPosterProfile(returnTo.address, returnTo.pubkey)
        }
    }

    /** Close-then-open (the profile Dialog covers the in-composition thread), remembering the way back. */
    fun openThreadFromProfile(returnTo: KaPostsProfileReturn, post: KaPostDraft) {
        profileReturns = profileReturns + (threadStack.size to returnTo)
        when (returnTo) {
            KaPostsProfileReturn.Mine -> showMyProfile = false
            is KaPostsProfileReturn.Poster -> viewModel.closePosterProfile()
        }
        openThread(post)
    }

    fun openShared(txId: String, focusReplyTxId: String? = null) {
        scope.launch {
            val post = viewModel.openSharedPost(txId)
            if (post != null) {
                threadFocusReplyId = focusReplyTxId
                openThread(post)
            } else {
                notFoundNotice = true
                delay(3_000)
                notFoundNotice = false
            }
        }
    }

    /** [openShared] for quoted embeds tapped inside a profile overlay: resolves the post first,
     *  then closes the profile and opens the thread with the way back remembered - without this
     *  the thread composed invisibly behind the profile's Dialog window. Not found: the profile
     *  just stays open (the feed's toast would be hidden behind the Dialog anyway). */
    fun openSharedFromProfile(returnTo: KaPostsProfileReturn, txId: String) {
        scope.launch {
            val post = viewModel.openSharedPost(txId)
            if (post != null) openThreadFromProfile(returnTo, post)
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
        val focusReplyTxId = KaPostsDeepLink.pendingFocusReplyTxId.value
        KaPostsDeepLink.pendingPostTxId.value = null
        KaPostsDeepLink.pendingFocusReplyTxId.value = null
        // "" is the tab-only sentinel (a notification with no target txid): landing on the
        // freshly-loaded feed is the whole job, nothing to deep-open.
        if (txId.isNotEmpty()) openShared(txId, focusReplyTxId)
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
                // Top chrome mirrors iOS's KaPostsPageView navigation bar: clickable connection
                // dot leading + centered balance (ConnectionStatusIndicator / BalanceToolbarLabel
                // toolbar items), then the bold left-aligned large title, then the hamburger
                // inline with the three feed tabs (KaPostsView.feedTabBar).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    // Same clickable dot as the chat-thread and broadcast-room headers: 32dp
                    // surface circle, 10dp live-status dot, opens the connection status page.
                    ConnectionDotButton(
                        onClick = { navController.navigate("connection_status") },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    BalanceTopBarLabel(modifier = Modifier.align(Alignment.Center))
                }
                Text(
                    text = "KaPosts",
                    color = colors.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showSideMenu = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = colors.textPrimary)
                    }
                    FeedTabsRow(
                        selected = selectedFeed,
                        onSelect = { viewModel.selectFeed(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
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
                    // Keyed on ONLY the stream this tab renders: Popular's re-sort no longer
                    // re-runs when just the Following stream ticks, and vice versa.
                    val tabSource =
                        if (tab == KaPostsViewModel.FeedTab.FOLLOWING) visibleFollowingPosts else visiblePosts
                    val pageFeed = remember(tab, tabSource) {
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
                    // Pull-to-refresh replaces the old header refresh button, wired to the same
                    // page-one reload. Await-then-endRefresh pattern (the pull joins its own
                    // load's completion; loadFeed has no throwing path out, so the spinner
                    // always ends) rather than keying off isLoadingFeed, which background
                    // reloads also drive.
                    val pullRefreshState = rememberPullToRefreshState()
                    LaunchedEffect(pullRefreshState.isRefreshing) {
                        if (pullRefreshState.isRefreshing) {
                            viewModel.loadFeed(tab)
                            pullRefreshState.endRefresh()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // An idle PullToRefreshContainer "hides" by translating a full
                            // container-height above its own position without clipping - on this
                            // screen that band is the tab row, so clip the indicator to this Box:
                            // invisible at rest, revealed only by a real pull.
                            .clipToBounds()
                            .nestedScroll(pullRefreshState.nestedScrollConnection),
                    ) {
                    if (feedError != null && pageFeed.isEmpty()) {
                        // Wrapped in a LazyColumn purely so pull-to-refresh works on an error
                        // tab too (same reason iOS wraps its empty feeds in a ScrollView).
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxSize()) {
                                    FeedEmptyState(
                                        title = "Couldn't load the feed",
                                        body = feedError ?: "",
                                        actionLabel = "Retry",
                                        onAction = { viewModel.refresh() },
                                    )
                                }
                            }
                        }
                    } else if (pageFeed.isEmpty() && !isLoading) {
                        // Same LazyColumn wrapper: an empty feed must still be pullable - the
                        // common bootstrap case while feeds are sparse.
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxSize()) {
                                    FeedEmptyState(
                                        title = if (tab == KaPostsViewModel.FeedTab.FOLLOWING) "Nothing here yet" else "No posts yet",
                                        body = if (tab == KaPostsViewModel.FeedTab.FOLLOWING)
                                            "Follow people from their posts and their content shows up here."
                                        else
                                            "Be the first to post something on the Kaspa network.",
                                        actionLabel = null,
                                        onAction = {},
                                    )
                                }
                            }
                        }
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
                                // Sliced per row (see collectSelectedAsState): a probe result
                                // arriving for ONE post recomposes that row alone, not every
                                // visible row - the previous per-tab collection still put the
                                // whole maps into every item lambda's captures, so each probe
                                // hit re-ran the whole viewport mid-scroll.
                                val isThreadRoot by remember(post.id, post.remoteId) {
                                    combine(viewModel.localThreadRoots, viewModel.threadRootFlags) { locals, flags ->
                                        post.id in locals ||
                                            (post.remoteId != null && flags[post.remoteId] == true)
                                    }.distinctUntilChanged()
                                }.collectAsState(initial = viewModel.isThreadRoot(post))
                                if (isThreadRoot) {
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
                    // Hard guarantee on top of the clipToBounds above: the indicator only
                    // composes while a pull is in progress or a refresh runs, so no layout
                    // change can ever park the resting circle over the feed.
                    if (pullRefreshState.verticalOffset > 0f || pullRefreshState.isRefreshing) {
                        PullToRefreshContainer(
                            state = pullRefreshState,
                            modifier = Modifier.align(Alignment.TopCenter),
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

            KaPostsToastLayer(
                viewModel = viewModel,
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
            quotedAvatarUrl = viewModel.senderProfiles.value[target.posterAddress],
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
            onClose = { closeTopThread() },
            onOpenNested = { nested -> openThread(nested) },
            onOpenProfile = { address, pubkey -> viewModel.openPosterProfile(address, pubkey) },
            onOpenShared = { txId -> openShared(txId) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            focusReplyRemoteId = threadFocusReplyId,
            onFocusReplyHandled = { threadFocusReplyId = null },
        )
    }

    // AFTER the thread overlay on purpose: openShared is reachable from inside a thread
    // ("Replying to a post - view it", quoted embeds), and when it was drawn inside the
    // Scaffold the opaque thread overlay covered it - a failed resolve looked like the tap
    // did nothing at all. As a later sibling it draws above the thread too.
    if (notFoundNotice) {
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 90.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
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
    }

    if (showMyProfile) {
        KaPostsProfileOverlay(
            address = viewModel.myAddress() ?: "",
            pubkey = null,
            isMine = true,
            viewModel = viewModel,
            navController = navController,
            onClose = { showMyProfile = false },
            // Close the profile dialog FIRST: the thread overlay composes inside the screen,
            // which a Dialog window always covers — without this the thread opened invisibly
            // behind the profile (same close-then-open pattern as the bookmarks overlay).
            // openThreadFromProfile also remembers the way back, so closing that thread
            // returns here instead of dumping the user on the feed.
            onOpenThread = { openThreadFromProfile(KaPostsProfileReturn.Mine, it) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            onOpenQuoted = { openSharedFromProfile(KaPostsProfileReturn.Mine, it) },
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
            // Same close-then-open as the my-profile/bookmarks overlays: the thread composes
            // behind this Dialog window, so commenting from a profile showed nothing. The
            // helper also remembers the way back, so closing that thread returns to this
            // profile instead of dumping the user on the feed.
            onOpenThread = { openThreadFromProfile(KaPostsProfileReturn.Poster(profile.address, profile.pubkey), it) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            onOpenQuoted = { openSharedFromProfile(KaPostsProfileReturn.Poster(profile.address, profile.pubkey), it) },
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
            // Post Activity and the repost icon were silent no-ops in bookmarks (the cell's
            // defaults); the overlays they raise are Dialog windows, which stack above the
            // bookmarks Dialog, so they open in place.
            onViewEngagement = { engagementTarget = it },
            onRepostTap = { repostHandler(it) },
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
    // iOS parity: a compact rounded card that hugs its options (no full-height drawer, no
    // empty void below the rows), top-left with a small inset, sliding in from the leading edge.
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 10.dp, top = 12.dp)
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .border(1.dp, colors.surfaceVariant, RoundedCornerShape(24.dp))
            .padding(vertical = 8.dp),
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

/**
 * The header's clickable connection dot (same look as the chat-thread and broadcast-room
 * headers), collecting the live node-health color INSIDE its own restart scope: color ticks
 * repaint this 32dp circle only, instead of recomposing the whole KaPosts screen body.
 */
@Composable
private fun ConnectionDotButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val dotColorHex by hiltViewModel<com.kachat.app.viewmodels.ConnectionViewModel>().dotColorHex.collectAsState()
    Box(
        modifier = modifier
            .size(32.dp)
            .background(colors.surface, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(10.dp).background(Color(dotColorHex), CircleShape))
    }
}

@Composable
private fun FeedTabsRow(
    selected: KaPostsViewModel.FeedTab,
    onSelect: (KaPostsViewModel.FeedTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(modifier = modifier.fillMaxWidth()) {
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
    // Per-address / per-post SLICES of the view model's whole-map stores (see
    // collectSelectedAsState): this cell recomposes only when ITS avatar, name, follow state
    // or countdowns change. Collecting the whole maps here subscribed every visible cell to
    // every entry, so each author profile arriving mid-scroll re-ran the entire viewport.
    val avatarUrl by viewModel.senderProfiles.collectSelectedAsState(post.posterAddress) { it[post.posterAddress] }
    val name = posterDisplayNameState(viewModel, post.posterAddress)
    val isFollowingPoster by viewModel.following.collectSelectedAsState(post.posterAddress) { post.posterAddress in it }
    val cellDeadlines by viewModel.undoDeadlines.collectSelectedAsState(post.id) {
        Triple(it["repost:${post.id}"], it["like:${post.id}"], it["dislike:${post.id}"])
    }
    var showOverflow by remember { mutableStateOf(false) }
    // Tapped link awaiting the Copy/Open choice (iOS parity: links never auto-open).
    var tappedLinkUrl by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

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
                    imageUrl = avatarUrl,
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
                        // Remembered per timestamp: the >7d branch allocates a SimpleDateFormat,
                        // which is not something to redo on every cell recomposition mid-scroll
                        // (same pattern as the chat thread's remembered ChatTimeFormat call).
                        text = remember(post.timestamp) { relativePostTime(post.timestamp) },
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    if (!isMine) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val isFollowing = isFollowingPoster
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
                // opens that user's profile, tapping a link opens the Copy/Open dialog (iOS
                // parity - links never auto-open). ClickableText consumes EVERY tap on the
                // body though, so plain-text taps must fall through to the row's open-thread
                // action by hand - the body covers most of the cell, and without this
                // "tap the post to open its thread" only worked on the padding around it.
                // Root cells keep body taps inert, matching their disabled row clickable.
                val postAnnotated = remember(post.text) { annotatedPostText(post.text) }
                androidx.compose.foundation.text.ClickableText(
                    text = postAnnotated,
                    style = TextStyle(color = colors.textPrimary, fontSize = 15.sp, lineHeight = 20.sp),
                    maxLines = if (foldText) 8 else Int.MAX_VALUE,
                    overflow = if (foldText) TextOverflow.Ellipsis else TextOverflow.Clip,
                    onClick = { offset ->
                        val mention = postAnnotated.getStringAnnotations(MENTION_ANNOTATION_TAG, offset, offset).firstOrNull()
                        val link = postAnnotated.getStringAnnotations(LINK_ANNOTATION_TAG, offset, offset).firstOrNull()
                        when {
                            mention != null -> viewModel.openMentionProfile(mention.item)
                            link != null -> tappedLinkUrl = link.item
                            !isRoot -> onOpenThread()
                        }
                    },
                )
                tappedLinkUrl?.let { url ->
                    AlertDialog(
                        onDismissRequest = { tappedLinkUrl = null },
                        containerColor = colors.surface,
                        title = { Text(url, color = colors.textPrimary, fontSize = 14.sp) },
                        confirmButton = {
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = {
                                    tappedLinkUrl = null
                                    uriHandler.openUri(url)
                                }) { Text("Open Link", color = KaspaTeal, fontWeight = FontWeight.Bold) }
                                TextButton(onClick = {
                                    tappedLinkUrl = null
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                }) { Text("Copy Link", color = KaspaTeal, fontWeight = FontWeight.Bold) }
                                TextButton(onClick = { tappedLinkUrl = null }) {
                                    Text("Cancel", color = colors.textSecondary)
                                }
                            }
                        },
                    )
                }
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
                    // Same per-address slices for the quoted author as for the cell's own.
                    val quotedAvatarUrl by viewModel.senderProfiles
                        .collectSelectedAsState(quoted.posterAddress) { it[quoted.posterAddress] }
                    val quotedName = posterDisplayNameState(viewModel, quoted.posterAddress)
                    Box(
                        modifier = Modifier.clickable(enabled = quoted.remoteId != null) {
                            quoted.remoteId?.let(onOpenQuoted)
                        },
                    ) {
                        QuotedEmbedCard(
                            quoted = quoted,
                            displayName = quotedName,
                            avatarUrl = quotedAvatarUrl,
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
                    repostDeadline = cellDeadlines.first,
                    likeDeadline = cellDeadlines.second,
                    dislikeDeadline = cellDeadlines.third,
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
private fun QuotedEmbedCard(quoted: KaPostDraft.QuotedRef, displayName: String, avatarUrl: String? = null) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        // iOS parity: 20dp avatar + name + timestamp header row, matching quotedEmbedCard.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(
                imageUrl = avatarUrl,
                fallbackText = displayName,
                size = 20.dp,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = displayName,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            quoted.timestamp?.let { ts ->
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = remember(ts) { relativePostTime(ts) },
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = quoted.text.ifBlank { "Reposted" },
            color = colors.textPrimary,
            fontSize = 13.sp,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Engagement row with in-icon undo countdowns

@Composable
private fun EngagementRow(
    post: KaPostDraft,
    commentCount: Int,
    // THIS post's live undo deadlines only (nullable = no countdown running). Passing the whole
    // undoDeadlines map made every cell's row recompose whenever any post anywhere started or
    // finished a countdown; primitives keep strong skipping effective.
    repostDeadline: Long?,
    likeDeadline: Long?,
    dislikeDeadline: Long?,
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
            deadline = null,
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
                deadline = repostDeadline,
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
            deadline = likeDeadline,
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
            deadline = dislikeDeadline,
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
            deadline = null,
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
    deadline: Long?,
    icon: @Composable () -> Unit,
    count: Int?,
    onTap: () -> Unit,
    onCancel: (String) -> Unit,
) {
    val colors = LocalAppColors.current
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

/**
 * Keeps the text caret inside [scroll]'s viewport.
 *
 * Compose foundation 1.6's legacy `BasicTextField` only asks an ancestor scroller to reveal the
 * caret ONCE, when the field gains focus (`CoreTextField` -> `bringSelectionEndIntoView`). After
 * that the caret is kept visible purely by the field's OWN internal scroller, and that scroller
 * skips its `coerceOffset` unless the caret rect itself moved - a shrinking container just clamps
 * the stale offset. So anything that shrinks the composer without the user typing (the IME
 * animating in, the @mention list appearing, a thread segment being stacked, a quote card being
 * attached) leaves the caret below the visible area, behind the keyboard.
 *
 * Running the field unbounded inside a real scroll container and driving that container from BOTH
 * the caret offset and [ScrollState.viewportSize] closes the hole: a viewport change is as good a
 * reason to re-reveal the caret as a keystroke. No keyboard-height arithmetic is involved - the
 * viewport already shrank because the composer is inset-padded for the IME.
 *
 * @param textTopPaddingPx distance from the top of the scroll content to the first text line
 *        (the field's own top padding), since the caret rect is relative to the text.
 */
@Composable
private fun KeepCaretVisible(
    scroll: ScrollState,
    value: TextFieldValue,
    layout: TextLayoutResult?,
    textTopPaddingPx: Int,
) {
    LaunchedEffect(value.selection, layout, scroll.viewportSize, scroll.maxValue) {
        val result = layout ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val caret = runCatching {
            result.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
        }.getOrNull() ?: return@LaunchedEffect
        val caretTop = caret.top + textTopPaddingPx
        val caretBottom = caret.bottom + textTopPaddingPx
        val current = scroll.value.toFloat()
        // Same padding again as breathing room, so the line being typed never sits flush against
        // the edge of the card (the scroll content already reserves it at both ends).
        val target = when {
            caretBottom > current + viewport -> caretBottom + textTopPaddingPx - viewport
            caretTop < current -> caretTop - textTopPaddingPx
            else -> return@LaunchedEffect
        }
        val max = scroll.maxValue
        if (max <= 0) return@LaunchedEffect
        scroll.scrollTo(target.roundToInt().coerceIn(0, max))
    }
}

@Composable
fun KaPostComposerDialog(
    title: String,
    quoted: KaPostDraft?,
    quotedDisplayName: String = "",
    quotedAvatarUrl: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    /** Enables @mention autocomplete (chips of 1:1 KNS-domain contacts) when provided. */
    viewModel: KaPostsViewModel? = null,
    /** Enables X-style thread posting (+ stacks segments; Post All submits the chain). */
    onSubmitThread: ((List<String>) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    // TextFieldValue rather than String: the caret offset is what [KeepCaretVisible] below needs
    // to scroll the editor to, and the String overload never exposes it.
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var threadSegments by remember { mutableStateOf(listOf<String>()) }
    val limit = KaPostDraft.POST_CHARACTER_LIMIT
    val totalSegments = threadSegments.size + (if (text.text.isNotBlank()) 1 else 0)
    val canPost = totalSegments > 0 && text.text.length <= limit
    val threadingEnabled = onSubmitThread != null && quoted == null

    // Warm the KNS caches so typing @ has domains to offer.
    LaunchedEffect(Unit) { viewModel?.prefetchMentionCandidates() }
    // The @token being typed at the END of the text ("" right after "@"), or null.
    val mentionQuery = remember(text.text) {
        MENTION_QUERY_REGEX
            .find(text.text)?.groupValues?.get(2)?.lowercase()
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
    val mentionSuggestions = remember(text.text, resolvedAnyDomain) {
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
                KaPostCharacterMeter(count = text.text.length)
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
                            val trimmed = text.text.trim()
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
                                    val replaced = text.text.replace(MENTION_REPLACE_REGEX, "@$domain ")
                                    // Caret to the end of the inserted mention, otherwise it would
                                    // snap back to offset 0 and the editor would scroll to the top.
                                    text = TextFieldValue(replaced, TextRange(replaced.length))
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
            //
            // The card is the SCROLL VIEWPORT and the field inside it grows with the text, rather
            // than the field being fillMaxSize() and relying on BasicTextField's own scroller.
            // That internal scroller (foundation's TextFieldScrollerPosition.update) only re-scrolls
            // to the caret when the CARET rect moves - when the container shrinks under it the old
            // offset is merely clamped, so every shrink that is not caused by typing (the IME
            // opening, the @mention list appearing, a thread segment being stacked, the quote card
            // arriving) left the caret parked below the visible area, i.e. behind the keyboard.
            // KeepCaretVisible below re-runs on viewport changes as well as caret changes.
            val editorScroll = rememberScrollState()
            var editorLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            var editorViewportPx by remember { mutableIntStateOf(0) }
            val editorDensity = LocalDensity.current
            KeepCaretVisible(
                scroll = editorScroll,
                value = text,
                layout = editorLayout,
                textTopPaddingPx = with(editorDensity) { 12.dp.roundToPx() },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, colors.textSecondary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .onSizeChanged { editorViewportPx = it.height },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(editorScroll),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.text.length <= limit) text = it },
                        onTextLayout = { editorLayout = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(KaspaTeal),
                        modifier = Modifier
                            .fillMaxWidth()
                            // Short drafts still fill the whole card, so a tap anywhere inside the
                            // border lands in the field exactly as it did when it was fillMaxSize().
                            .heightIn(min = with(editorDensity) { editorViewportPx.toDp() })
                            .padding(12.dp),
                        decorationBox = { inner ->
                            if (text.text.isEmpty()) {
                                Text(
                                    if (threadSegments.isEmpty()) "What's happening on Kaspa?" else "Add another post",
                                    color = colors.textSecondary,
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
                if (threadingEnabled && text.text.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(KaspaTeal.copy(alpha = 0.15f))
                            .clickable {
                                threadSegments = threadSegments + text.text.trim()
                                text = TextFieldValue("")
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
                        avatarUrl = quotedAvatarUrl,
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
    /** RemoteId of a reply to scroll to once it lands (reply-notification taps open the
     *  PARENT's thread and hand the reply's txid through here). */
    focusReplyRemoteId: String? = null,
    onFocusReplyHandled: () -> Unit = {},
) {
    val colors = LocalAppColors.current
    // Resolving against the post tree (rather than taking a KaPostDraft parameter) is what
    // makes the thread live: fetched replies, inline-expanded sub-threads and optimistic
    // replies all land inside the view model's post lists, and this re-reads them. The tree
    // is deliberately NOT read in composition: it re-emits on every background mutation
    // (feed polls, sender-profile fetches, undo timers anywhere in KaPosts), and each
    // emission recomposed this whole overlay — which is what made the reply box need several
    // taps before it would focus (a tap landing mid-recomposition gets its press cancelled).
    // Collecting in an effect and republishing only STRUCTURALLY CHANGED posts means the
    // overlay recomposes exactly when this thread's content actually changed.
    var postState by remember(postId) { mutableStateOf(viewModel.findPost(postId)) }
    LaunchedEffect(postId) {
        viewModel.postTree.collect {
            val fresh = viewModel.findPost(postId)
            if (fresh != postState) postState = fresh
        }
    }
    val post = postState
    if (post == null) {
        // The thread's post vanished (feed refresh dropped it) - pop this level.
        LaunchedEffect(postId) { onClose() }
        return
    }
    /** Which post in this thread the composer targets; null = the thread root. */
    var replyTargetId by remember(postId) { mutableStateOf<String?>(null) }
    // Tapping a comment's reply bubble retargets the composer AND raises the keyboard in the
    // same gesture — before this, the user had to land a second tap on the field itself.
    val replyFieldFocus = remember { FocusRequester() }
    LaunchedEffect(replyTargetId) {
        if (replyTargetId != null) {
            runCatching { replyFieldFocus.requestFocus() }
        }
    }
    // Zero-balance funding gate — tapping the reply composer while the chatting balance is a
    // confirmed 0 KAS opens the shared funding card instead of the reply field/keyboard.
    val fundingGate = rememberZeroBalanceFundingGate()
    var showFundingGate by remember { mutableStateOf(false) }
    var expandedIds by remember(postId) { mutableStateOf(setOf<String>()) }
    val muted by viewModel.muted.collectAsState()
    val blocked by viewModel.blocked.collectAsState()
    val hidden = remember(muted, blocked) { muted + blocked }
    // The author's own continuation renders as a connected Thread section under the root;
    // its segments are excluded from the comment list (segment 2 IS a direct reply).
    // Sliced per post: another thread's chain landing no longer recomposes this overlay.
    val threadChain by viewModel.threadChains.collectSelectedAsState(post.id) { it[post.id].orEmpty() }
    val chainRemoteIds = remember(threadChain) { threadChain.mapNotNull { it.remoteId }.toSet() }
    val visibleComments = remember(post, hidden, chainRemoteIds) {
        post.comments.filter {
            it.posterAddress !in hidden && (it.remoteId == null || it.remoteId !in chainRemoteIds)
        }
    }
    // Falls back to the root whenever the targeted comment is gone (a refresh replaced it).
    val replyTarget = remember(replyTargetId, post) {
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

    // Scroll-to-the-new-reply for notification landings. Re-keys on the comment list until the
    // focused reply is actually among the loaded comments (page one may still be in flight),
    // then scrolls once and clears the focus so nothing re-scrolls later. Index math mirrors
    // the LazyColumn below: root-context + root, then the optional chain section
    // (header + segments + divider), then the comments.
    if (focusReplyRemoteId != null) {
        LaunchedEffect(visibleComments, threadChain, focusReplyRemoteId) {
            val commentIndex = visibleComments.indexOfFirst { it.remoteId == focusReplyRemoteId }
            if (commentIndex >= 0) {
                val chainItems = if (threadChain.isEmpty()) 0 else threadChain.size + 2
                runCatching { threadListState.animateScrollToItem(2 + chainItems + commentIndex) }
                onFocusReplyHandled()
            }
        }
    }

    // System back closes this level of the thread, matching the Dialog's dismiss behaviour and the
    // Back arrow in the header. Nested pushes each get their own overlay instance, so back walks
    // the thread stack down one level at a time.
    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            // Claim ONLY horizontal drags (which the ancestor feed pager would otherwise read
            // as a tab swipe). The previous blanket every-unconsumed-change consumer here also
            // ate MOVE events — and a descendant TextField's tap detector cancels the moment it
            // sees a consumed change mid-gesture, so any tap on the reply box with a pixel of
            // finger drift silently did nothing (the "tap several times before I can type" bug).
            // Taps never fall through: sibling content behind this opaque overlay loses the
            // hit test, and the pager ignores taps.
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, _ -> }
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
            ThreadReplyComposer(
                viewModel = viewModel,
                postId = postId,
                replyingToComment = replyingToComment,
                replyTargetName = if (replyingToComment) viewModel.posterDisplayName(replyTarget.posterAddress) else "",
                fundingGateActive = fundingGate.active,
                focusRequester = replyFieldFocus,
                onShowFundingGate = { showFundingGate = true },
                onClearReplyTarget = { replyTargetId = null },
                onSubmit = { text ->
                    // Replies nest against their IMMEDIATE parent (postId + mention = that
                    // comment and its author), which is what the indexer keys get-replies on.
                    val target = replyTarget
                    viewModel.submitReply(target, text)
                    // Reveal the new reply straight away: a comment's children only render
                    // while it's expanded.
                    if (target.id != post.id && target.id !in expandedIds) {
                        viewModel.expandReplies(target)
                        expandedIds = expandedIds + target.id
                    }
                    replyTargetId = null
                },
            )
        }
        // The main screen's toast layer sits BEHIND this opaque overlay — without a copy in
        // here, a like/repost/reply made from an open thread showed no undo toast and no
        // network confirmation, which read as the buttons doing nothing at all for the whole
        // 5-second undo window (and made Undo unreachable). Its flows are collected inside
        // KaPostsToastLayer's own restart scope so toast emissions never recompose the overlay.
        KaPostsToastLayer(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
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



/** The undo/confirmation toast stack in its OWN restart scope (used by the main screen and the
 *  thread overlay): toast emissions recompose this layer only, never the screen or list behind. */
@Composable
private fun KaPostsToastLayer(viewModel: KaPostsViewModel, modifier: Modifier = Modifier) {
    val undoToast by viewModel.undoToast.collectAsState()
    val actionToast by viewModel.actionToast.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current
    KaPostsToastOverlay(
        undoToast = undoToast,
        actionToast = actionToast,
        onUndo = { viewModel.undoPendingPost() },
        onViewTx = { uriHandler.openUri(kaspaExplorer.txUrl(it)) },
        modifier = modifier,
    )
}

/**
 * The thread's pinned reply composer, extracted into its OWN restart scope: replyText lives
 * here, so a keystroke recomposes only this composable — previously every character re-ran the
 * whole thread overlay (every visible comment included), which was the reported typing lag.
 */
@Composable
private fun ThreadReplyComposer(
    viewModel: KaPostsViewModel,
    postId: String,
    replyingToComment: Boolean,
    replyTargetName: String,
    fundingGateActive: Boolean,
    focusRequester: FocusRequester,
    onShowFundingGate: () -> Unit,
    onClearReplyTarget: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    var replyText by remember(postId) { mutableStateOf("") }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (fundingGateActive) 0.35f else 1f),
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
                        text = "Replying to ${replyTargetName}",
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
                            .clickable { onClearReplyTarget() },
                    )
                }
            }
            // @mention autocomplete for COMMENTS - identical machinery to the post
            // composer: KNS domains of everyone you've chatted with, plus a live
            // any-KNS resolve of the typed query. Shown above the input so the
            // keyboard can never hide it.
            LaunchedEffect(Unit) { viewModel.prefetchMentionCandidates() }
            val replyMentionQuery = remember(replyText) {
                MENTION_QUERY_REGEX
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
                                    replyText = replyText.replace(MENTION_REPLACE_REGEX, "@$domain ")
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
                    // Load-bearing cap, same idea as the chat composer's maxLines = 4. Without it
                    // this field has no height bound at all: Column measures the pinned composer
                    // BEFORE the weighted thread list, so a long reply grew until it had eaten the
                    // entire overlay, and - because the field's own scroll container only starts
                    // clamping once it hits that ceiling - the caret rode the bottom edge down
                    // behind the IME on the way there. Capped at six lines the container size is
                    // constant, so foundation's internal caret scroller keeps the caret in view.
                    maxLines = 6,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
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
                        onSubmit(replyText.trim())
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
        }
        if (fundingGateActive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onShowFundingGate() }
            )
        }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // Per-address slices for THIS profile's chrome (see collectSelectedAsState): the rows
    // below trigger a KNS fetch per author as they scroll in, and collecting the whole maps
    // here meant every one of those results recomposed the entire overlay - banner, header
    // and both pager pages - mid-scroll.
    val profileAvatarUrl by viewModel.senderProfiles.collectSelectedAsState(address) { it[address] }
    val profileBannerUrl by viewModel.senderBanners.collectSelectedAsState(address) { it[address] }
    val profileBio by viewModel.senderBios.collectSelectedAsState(address) { it[address] }
    val following by viewModel.following.collectAsState()
    val posterProfile by viewModel.posterProfile.collectAsState()
    val myFollowersCount by viewModel.myFollowersCount.collectAsState()
    val isLoadingMyProfile by viewModel.isLoadingMyProfile.collectAsState()
    val myProfileReplies by viewModel.myProfileReplies.collectAsState()
    val posterPosts by viewModel.posterProfilePosts.collectAsState()
    val posterReplies by viewModel.posterProfileReplies.collectAsState()
    // Recompose against the live lists so engagement changes show immediately - but the
    // merge-and-sort only re-runs when one of its inputs actually changed, instead of on
    // every recomposition of the overlay.
    val localPosts by viewModel.localPosts.collectAsState()
    val myProfilePosts by viewModel.myProfilePosts.collectAsState()
    val myPostsList = if (isMine) {
        remember(localPosts, myProfilePosts) { viewModel.myCombinedPosts() }
    } else posterPosts
    val repliesList = if (isMine) myProfileReplies else posterReplies

    var selectedTab by remember { mutableStateOf(0) } // 0 = Posts, 1 = Replies
    val name = posterDisplayNameState(viewModel, address)
    // Swipeable Posts/Replies tabs - the same two-way pager<->tab-row sync the feed tabs use
    // (see the feedPagerState comments in KaPostsScreen): a swipe selects the tab once the page
    // settles, a tab tap animates the pager across, and each direction no-ops once the other has
    // caught up so they can't ping-pong. One LazyListState per tab, hoisted here so each list
    // keeps its scroll offset across swipes (the pager disposes off-screen pages).
    val profilePagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val postsListState = rememberLazyListState()
    val repliesListState = rememberLazyListState()
    val profileListStates = remember(postsListState, repliesListState) {
        listOf(postsListState, repliesListState)
    }
    LaunchedEffect(profilePagerState) {
        snapshotFlow { profilePagerState.settledPage }.collect { page ->
            if (page != selectedTab) selectedTab = page
        }
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab != profilePagerState.currentPage) {
            profilePagerState.animateScrollToPage(selectedTab)
        }
    }
    // Posts and Replies are separate paging surfaces (separate endpoints, separate cursors), so
    // each pager page carries its own load-more trigger and footer.
    val profilePubkey = remember(isMine, pubkey, posterProfile?.pubkey) { viewModel.profilePubkey(isMine) }

    LaunchedEffect(address) { viewModel.ensureSenderProfileFetched(address) }

    Dialog(
        onDismissRequest = onClose,
        properties = KaPostsFullScreenDialogProperties,
    ) {
        // Same window-sizing fix as the other overlays. This one deliberately keeps its content
        // edge-to-edge at the top (the KNS banner runs under the status bar), so it takes the
        // navigation-bar inset on the list's bottom instead of padding the whole Column.
        ForceFullScreenDialogWindow()
        Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
            // Fixed chrome: banner, avatar row, header, and the tab row stay pinned while only
            // the content below slides between Posts and Replies (matching iOS's profile tabs).
            // Divergence from iOS worth knowing: iOS puts the whole profile in one ScrollView,
            // so its banner scrolls away with the posts; here the chrome is static so the pager
            // underneath can own the horizontal swipe.
            Box {
                val bannerUrl = profileBannerUrl
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
                            imageUrl = profileAvatarUrl,
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
            Column(modifier = Modifier.padding(horizontal = 16.dp).offset(y = (-26).dp)) {
                Text(name, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
                profileBio?.let { bio ->
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
            // The same HorizontalPager the feed tabs use, holding ONLY the per-tab post list
            // under the fixed chrome. Draggable paging is safe here for the same reason as the
            // feed: post cells carry no row-level horizontal gestures, and the menus a cell
            // opens are separate Dialog windows that never see this pager's drags. Vertical
            // scrolling nests inside the pager the same way the feed's lists do.
            HorizontalPager(
                state = profilePagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { it },
            ) { page ->
                val repliesPage = page == 1
                val pageItems = if (repliesPage) repliesList else myPostsList
                val pagePaging = pagingStateOf(
                    viewModel,
                    profilePubkey?.let { KaPostsViewModel.pageProfile(it, isMine, repliesPage) } ?: "profile:none",
                )
                EndlessScroll(listState = profileListStates[page], key = page to profilePubkey) {
                    viewModel.loadMoreProfile(isMine, replies = repliesPage)
                }
                LazyColumn(
                    state = profileListStates[page],
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                ) {
                    val items = pageItems
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
                                        if (repliesPage) "No replies yet" else "No posts yet",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        if (repliesPage) "Replies will show up here." else "Posts will show up here.",
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
                                // The fallback navigates the NAV HOST, which this profile Dialog
                                // window covers - close the profile first or the chat screen
                                // only becomes visible after the user closes it themselves.
                                onTip = onTip?.let { open -> { open(post) } }
                                    ?: { onClose(); navController.navigate("chat/${post.posterAddress}?paymentMode=true") },
                            )
                            HorizontalDivider(
                                color = colors.surfaceVariant,
                                modifier = Modifier.padding(start = 68.dp),
                            )
                        }
                        pagingFooter(pagePaging, keySuffix = "profile-$page") {
                            viewModel.loadMoreProfile(isMine, replies = repliesPage)
                        }
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
                    // Per-actor slices: one avatar/name landing repaints its own row only.
                    val actorAvatar by viewModel.senderProfiles
                        .collectSelectedAsState(item.actorAddress) { it[item.actorAddress] }
                    val actorName = posterDisplayNameState(viewModel, item.actorAddress)
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
                            imageUrl = actorAvatar,
                            fallbackText = actorName,
                            size = 38.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$actorName ${notificationActionText(item.kind)}",
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
                            Text(
                                remember(item.timestampMs) { relativePostTime(item.timestampMs) },
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                            )
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

/** @mention machinery, hoisted: these were compiled per keystroke inside remember blocks. */
private val MENTION_QUERY_REGEX = Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]*)$", RegexOption.IGNORE_CASE)
private val MENTION_REPLACE_REGEX = Regex("@[a-z0-9-]*$", RegexOption.IGNORE_CASE)

/** Annotation tag carried by @mention ranges - ClickableText resolves it to a profile. */
const val MENTION_ANNOTATION_TAG = "mention"

/** Annotation tag carried by URL ranges - ClickableText opens a Copy/Open dialog (iOS parity:
 *  a link tap never auto-opens the browser). Value = normalized (https-prefixed) URL. */
const val LINK_ANNOTATION_TAG = "link"

/** Detected links in post text: http(s) URLs plus bare www. hosts, like iOS's linkifier. */
private val POST_URL_REGEX = Regex("""(?i)\b(?:https?://|www\.)\S+""")

/** Post text with @mention tokens tinted teal AND annotated for tap-to-profile, and URLs
 *  tinted+underlined AND annotated for the tap-to-Copy/Open dialog. */
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
        for (match in POST_URL_REGEX.findAll(text)) {
            // Trailing sentence punctuation isn't part of the link ("see https://kaspa.org.").
            val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
            if (raw.isEmpty()) continue
            val start = match.range.first
            val end = start + raw.length
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = KaspaTeal,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
                start,
                end,
            )
            addStringAnnotation(
                LINK_ANNOTATION_TAG,
                if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw,
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
                    // Per-address slices: one avatar/name landing repaints its own row only.
                    val entryAvatar by viewModel.senderProfiles
                        .collectSelectedAsState(entry.address) { it[entry.address] }
                    val entryName = posterDisplayNameState(viewModel, entry.address)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        ContactAvatar(
                            imageUrl = entryAvatar,
                            fallbackText = entryName,
                            size = 38.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entryName,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            entry.timestampMs?.let {
                                Text(
                                    remember(it) { relativePostTime(it) },
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                )
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
                            // Per-actor slices: one avatar/name landing repaints its own row only.
                            val actorAvatar by viewModel.senderProfiles
                                .collectSelectedAsState(entry.actorAddress) { it[entry.actorAddress] }
                            val actorName = posterDisplayNameState(viewModel, entry.actorAddress)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                ContactAvatar(
                                    imageUrl = actorAvatar,
                                    fallbackText = actorName,
                                    size = 38.dp,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        actorName,
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        remember(entry.timestampMs) { relativePostTime(entry.timestampMs) },
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                    )
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
    val addresses = remember(blocked, mutedSet, blockedSet) {
        (if (blocked) blockedSet else mutedSet).sorted()
    }

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
                    // Per-address slices: one avatar/name landing repaints its own row only.
                    val rowAvatar by viewModel.senderProfiles
                        .collectSelectedAsState(address) { it[address] }
                    val rowName = posterDisplayNameState(viewModel, address)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        ContactAvatar(
                            imageUrl = rowAvatar,
                            fallbackText = rowName,
                            size = 38.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            rowName,
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
    onViewEngagement: (KaPostDraft) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
) {
    val colors = LocalAppColors.current
    // Recompute against the live lists so un-bookmarking updates immediately - but the
    // full-tree scan only re-runs when one of those lists actually changed.
    val localPosts by viewModel.localPosts.collectAsState()
    val feed by viewModel.visibleFeed.collectAsState()
    val bookmarks = remember(localPosts, feed) { viewModel.bookmarkedPosts() }

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
                        onRepostTap = { onRepostTap(post) },
                        onViewEngagement = { onViewEngagement(post) },
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
