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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.StrikethroughS
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.withStyle
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.util.KaspaAddress
import com.kachat.app.viewmodels.ChatViewModel
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.models.KaPostDraft
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaPostsMarkdown
import com.kachat.app.services.PostTranslationService
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
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Badge
import androidx.compose.ui.text.style.TextAlign

/**
 * Cross-screen deep-link handoff: MainActivity (kachat://kapost/<txid>, universal links) and
 * notification taps set this; KaPostsScreen consumes it and opens the post's thread.
 */
object KaPostsDeepLink {
    val pendingPostTxId = MutableStateFlow<String?>(null)

    /** For reply notifications: the reply's own txid, so the opened PARENT thread (which is
     *  what [pendingPostTxId] carries) can scroll to the new comment. */
    val pendingFocusReplyTxId = MutableStateFlow<String?>(null)

    /** Set alongside a blank [pendingPostTxId] when a KaPosts notification names no post to open
     *  (a follow, or a push whose payload carried no post id): KaPostsScreen opens its
     *  Notifications list instead of just dropping the user on the feed with no idea what the
     *  ping was about. Mirrors iOS's `KaPostsDeepLink.pendingOpenNotifications`. */
    val pendingOpenNotifications = MutableStateFlow(false)
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
    if (!state.isLoadingMore && state.error == null && !state.stalled) return
    item(key = "paging-footer-$keySuffix") {
        PagingFooterContent(state = state, onLoadMore = onRetry)
    }
}

/**
 * The footer's three states, as iOS's KaPostsLoadMoreFooter draws them: a spinner with
 * "Loading more...", a tappable "Couldn't load more" block carrying the error and "Tap to retry",
 * or - after a whole request budget produced nothing visible - an explicit "Load more" button.
 * Shared by the lazy lists and the inline reply expansions, which are not lists of their own.
 */
@Composable
private fun PagingFooterContent(
    state: KaPostsViewModel.PagingState,
    onLoadMore: () -> Unit,
) {
    val colors = LocalAppColors.current
    when {
        state.isLoadingMore -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.textSecondary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Loading more...", color = colors.textSecondary, fontSize = 12.sp)
        }
        state.error != null -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLoadMore() }
                .padding(vertical = 16.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Couldn't load more", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(state.error, color = colors.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
            Text("Tap to retry", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        state.stalled && state.hasMore -> Text(
            "Load more",
            color = KaspaTeal,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLoadMore() }
                .padding(vertical = 16.dp),
        )
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

/** The three translation flags a cell reads, as one value so it can be one collector. */
private data class KaPostTranslationSlice(
    val state: com.kachat.app.services.PostTranslationService.TranslationState?,
    val showingOriginal: Boolean,
    val canTranslate: Boolean,
)

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

    var showComposer by remember { mutableStateOf(false) }
    /// Text handed back by Undo, for the composer that is about to reopen.
    var restoredComposerText by remember { mutableStateOf("") }
    /// Thread segments handed back by Undo, stacked above the restored text.
    var restoredComposerSegments by remember { mutableStateOf(emptyList<String>()) }
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
    var quoteTarget by remember { mutableStateOf<KaPostDraft?>(null) }
    /**
     * Answering a SPECIFIC reply opens the composer with that reply under the editor, as on iOS
     * and desktop. The inline box under the post you opened stays for replying to the post
     * itself - X has both shapes, each where it fits.
     */
    var replyComposerTarget by remember { mutableStateOf<KaPostDraft?>(null) }
    /** The post a reopened reply or quote draft was about, once resolved from its stored txid. */
    var draftSourcePost by remember { mutableStateOf<KaPostDraft?>(null) }
    /** The reply box under the opened post. Lives here rather than in the thread overlay so it
     *  survives walking up and down the thread stack; cleared only when the thread closes (iOS
     *  replyText / closeThread). */
    var threadReplyText by remember { mutableStateOf(TextFieldValue("")) }
    // Anything handed back by Undo reopens its composer with the words still in it - a post or
    // thread reopens the composer, a quote reopens the quote composer on its target, and an
    // undone comment reopens the Reply composer with its parent attached (iOS restoreDraft).
    // Deferred a beat so the toast's own dismissal animation has somewhere to land.
    val restoredDraft by viewModel.restoredDraft.collectAsState()
    LaunchedEffect(restoredDraft) {
        val draft = restoredDraft ?: return@LaunchedEffect
        // Cleared LAST: this effect is keyed on the draft, so clearing it first would cancel
        // the very coroutine waiting out the delay.
        delay(300)
        if (draft.isComment) {
            val parent = draft.commentParentId?.let { viewModel.findPost(it) }
            if (parent != null) {
                restoredComposerText = draft.text
                replyComposerTarget = parent
            }
        } else {
            restoredComposerText = draft.text
            restoredComposerSegments = draft.threadSegments
            val target = draft.quoteTargetId?.let { viewModel.findPost(it) }
            if (target != null) quoteTarget = target else showComposer = true
        }
        viewModel.clearRestoredDraft()
    }
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
    var showSearch by remember { mutableStateOf(false) }
    val kaPostsUnseen by viewModel.unseenNotifications.collectAsState()
    // Opening the list IS seeing them - cleared on open rather than on close so the badge does
    // not sit there while you read. While it is open the poller holds its banners (iOS).
    LaunchedEffect(showNotifications) {
        if (showNotifications) viewModel.markNotificationsSeen()
        viewModel.setNotificationsScreenVisible(showNotifications)
    }
    DisposableEffect(Unit) { onDispose { viewModel.setNotificationsScreenVisible(false) } }
    var followListKind by remember { mutableStateOf<Boolean?>(null) } // true = followers
    // The profile whose follow list is open: null = my own list, non-null = another user's.
    var followListPubkey by remember { mutableStateOf<String?>(null) }
    // Quick-tip dialog target: (poster address, display name).
    var tipTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moderationKind by remember { mutableStateOf<Boolean?>(null) } // true = blocked
    var showBookmarks by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf<KaPostSavedDraft?>(null) }
    val draftContext = LocalContext.current
    val myAddressForDrafts = viewModel.myAddress()
    var drafts by remember { mutableStateOf(emptyList<KaPostSavedDraft>()) }
    fun reloadDrafts() { drafts = KaPostDraftStore.load(draftContext, myAddressForDrafts.orEmpty()) }

    val posterProfile by viewModel.posterProfile.collectAsState()
    val deepLinkTxId by KaPostsDeepLink.pendingPostTxId.collectAsState()

    // Tab-driven feed pager (no drag - see the HorizontalPager below). One LazyListState per
    // tab, hoisted here rather than remembered inside the pager page, so each feed keeps its
    // scroll offset across switches.
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
    LaunchedEffect(threadOpen) {
        walletViewModel.setHideBottomBar(threadOpen)
        // Out of the thread entirely: the reply box starts empty next time (iOS closeThread).
        if (!threadOpen) threadReplyText = TextFieldValue("")
    }
    DisposableEffect(Unit) { onDispose { walletViewModel.setHideBottomBar(false) } }

    /** Already reading a thread: push, so Back returns here. Re-opening the post already on top
     *  is a no-op, so a double tap cannot stack it twice (iOS openDetail). */
    fun openThread(post: KaPostDraft) {
        if (threadStack.lastOrNull() != post.id) threadStack = threadStack + post.id
    }

    /** Opens where a resolved post lands: its own thread, or - for a reply - its parent's, scrolled
     *  to the reply. */
    fun openLanding(landing: KaPostsViewModel.ThreadLanding, focusReplyTxId: String? = null) {
        threadFocusReplyId = landing.scrollToRemoteId ?: focusReplyTxId
        openThread(landing.post)
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
    fun openLandingFromProfile(returnTo: KaPostsProfileReturn, landing: KaPostsViewModel.ThreadLanding) {
        profileReturns = profileReturns + (threadStack.size to returnTo)
        when (returnTo) {
            KaPostsProfileReturn.Mine -> showMyProfile = false
            is KaPostsProfileReturn.Poster -> viewModel.closePosterProfile()
        }
        openLanding(landing)
    }

    /** A post tapped on a profile: a reply opens the post it answers, with the reply scrolled
     *  into view, the rule notifications and shared links follow (iOS openProfileDetail). */
    fun openThreadFromProfile(returnTo: KaPostsProfileReturn, post: KaPostDraft) {
        scope.launch { openLandingFromProfile(returnTo, viewModel.landingFor(post)) }
    }

    fun openShared(txId: String, focusReplyTxId: String? = null) {
        scope.launch {
            val landing = viewModel.openSharedPost(txId)
            if (landing != null) openLanding(landing, focusReplyTxId) else viewModel.showPostNotFound(txId)
        }
    }

    /** [openShared] for quoted embeds tapped inside a profile overlay: resolves the post first,
     *  then closes the profile and opens the thread with the way back remembered - without this
     *  the thread composed invisibly behind the profile's Dialog window. Not found: the profile
     *  just stays open (the feed's toast would be hidden behind the Dialog anyway). */
    fun openSharedFromProfile(returnTo: KaPostsProfileReturn, txId: String) {
        scope.launch {
            val landing = viewModel.openSharedPost(txId)
            if (landing != null) openLandingFromProfile(returnTo, landing)
        }
    }

    /** Jump to a rung of the chain above the open post: unwind the stack to it when it is on the
     *  path already, otherwise start a fresh stack there - the rungs above what was walked were
     *  resolved from the loaded tree, not navigated to (iOS jumpToAncestor). */
    fun jumpToAncestor(ancestor: KaPostDraft) {
        threadFocusReplyId = null
        val index = threadStack.indexOf(ancestor.id)
        if (index >= 0) {
            threadStack = threadStack.take(index + 1)
        } else {
            profileReturns = emptyMap()
            threadStack = listOf(ancestor.id)
            viewModel.reloadThread(ancestor)
        }
    }

    LaunchedEffect(Unit) {
        // Page one, once per session: coming back to the tab keeps the feed and the place in it
        // (iOS's KaPostsView is kept alive by its tab view and loads in a one-shot task).
        viewModel.loadFeedIfNeeded()
        viewModel.refreshTranslationLanguages()
    }
    LaunchedEffect(deepLinkTxId) {
        val txId = deepLinkTxId ?: return@LaunchedEffect
        val focusReplyTxId = KaPostsDeepLink.pendingFocusReplyTxId.value
        KaPostsDeepLink.pendingPostTxId.value = null
        KaPostsDeepLink.pendingFocusReplyTxId.value = null
        // "" is the tab-only sentinel (a notification with no target txid). Nothing to
        // deep-open, but the ping still has to land somewhere that explains itself: open the
        // Notifications list (follows, and pushes whose payload named no post), matching iOS.
        if (txId.isNotEmpty()) {
            KaPostsDeepLink.pendingOpenNotifications.value = false
            openShared(txId, focusReplyTxId)
        } else if (KaPostsDeepLink.pendingOpenNotifications.value) {
            KaPostsDeepLink.pendingOpenNotifications.value = false
            showNotifications = true
        }
    }

    // The repost icon's plain tap only reaches here for a post with no txid yet (on-chain posts
    // open the Repost/Quote sheet instead): that just flips the flag locally, as on iOS.
    val repostHandler: (KaPostDraft) -> Unit = { post -> viewModel.scheduleRepost(post) }

    Scaffold(
        containerColor = colors.background,
        floatingActionButton = {
            // iOS createPostButton: a 56pt circle in the material fill with the pencil in accent,
            // not a filled accent button - the chat list's create-chat button shape.
            FloatingActionButton(
                onClick = { if (fundingGate.active) showFundingGate = true else showComposer = true },
                shape = CircleShape,
                containerColor = colors.surface,
                contentColor = KaspaTeal,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.Edit, contentDescription = "New post", modifier = Modifier.size(22.dp))
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
                        onClick = { ConnectionStatusOverlayState.open() },
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
                // Profile / Notifications / Bookmarks / Muted / Blocked, as the icons themselves
                // rather than behind a hamburger. Their own row above the feed tabs, left-aligned
                // where the hamburger used to be: five icons and three tabs do not fit one row on
                // a phone, and the point of the change is that every destination is one tap,
                // which a cramped row would undo.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KaPostsMenuIcon(Icons.Default.AccountCircle, "Profile") {
                        showMyProfile = true; viewModel.loadMyProfile()
                    }
                    KaPostsMenuIcon(Icons.Default.Search, "Search") { showSearch = true }
                    KaPostsMenuIcon(
                        Icons.Default.Notifications,
                        "Notifications",
                        onClick = { showNotifications = true },
                        badgeCount = kaPostsUnseen,
                    )
                    KaPostsMenuIcon(Icons.Default.EditNote, "Drafts") {
                        drafts = KaPostDraftStore.load(draftContext, myAddressForDrafts.orEmpty())
                        showDrafts = true
                    }
                    KaPostsMenuIcon(Icons.Default.BookmarkBorder, "Bookmarks") { showBookmarks = true }
                    KaPostsMenuIcon(Icons.Default.VolumeOff, "Muted") { moderationKind = false }
                    KaPostsMenuIcon(Icons.Default.Block, "Blocked") { moderationKind = true }
                    Spacer(Modifier.weight(1f))
                }
                FeedTabsRow(
                    selected = selectedFeed,
                    onSelect = { viewModel.selectFeed(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(color = colors.surfaceVariant)

                // The tab row is the only way between feeds - a tab tap still animates the page
                // across, but the pager takes no drags of its own. A swipe that silently changes
                // which feed you are reading is easy to trigger by accident while scrolling, and
                // there is nothing on screen afterwards to explain why the posts changed. The
                // pager stays (rather than a plain when-on-tab) so each feed keeps its own scroll
                // position and the switch is still animated.
                HorizontalPager(
                    state = feedPagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    userScrollEnabled = false,
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
                    val pendingNew by viewModel.pendingNewPosts.collectAsState()
                    val newPostsScope = rememberCoroutineScope()
                    // Only while this feed is the one on screen: the effect is torn down on a tab
                    // swipe, so nothing polls for a feed nobody is looking at.
                    if (tab == selectedFeed) {
                        LaunchedEffect(tab) {
                            while (true) {
                                kotlinx.coroutines.delay(viewModel.newPostsCheckIntervalMs)
                                viewModel.checkForNewPosts(tab)
                            }
                        }
                    }
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
                                Box(modifier = Modifier.fillParentMaxHeight(0.8f).fillMaxWidth()) {
                                    FeedEmptyState(
                                        icon = when (tab) {
                                            KaPostsViewModel.FeedTab.FOLLOWING -> Icons.Default.Group
                                            KaPostsViewModel.FeedTab.FEED -> Icons.Default.Edit
                                            KaPostsViewModel.FeedTab.POPULAR -> Icons.Default.LocalFireDepartment
                                        },
                                        title = when (tab) {
                                            KaPostsViewModel.FeedTab.FOLLOWING -> "Nothing from people you follow"
                                            KaPostsViewModel.FeedTab.FEED -> "No posts yet"
                                            KaPostsViewModel.FeedTab.POPULAR -> "Nothing trending yet"
                                        },
                                        body = when (tab) {
                                            KaPostsViewModel.FeedTab.FOLLOWING -> "Posts from accounts you follow will show up here."
                                            KaPostsViewModel.FeedTab.FEED -> "Be the first - tap the pencil to write a post."
                                            KaPostsViewModel.FeedTab.POPULAR -> "The most liked, reposted and talked-about posts will show up here."
                                        },
                                        actionLabel = null,
                                        onAction = {},
                                    )
                                }
                            }
                            // Everything fetched so far was filtered away (all muted, or - on
                            // Following - none of it from accounts you follow locally) while the
                            // server still has older pages. No auto-sentinel here: with nothing on
                            // screen it would walk the whole history unattended, so this stays an
                            // explicit tap (iOS "Load older posts").
                            item {
                                if (!feedPaging.isLoadingMore && feedPaging.hasMore && feedPaging.cursor != null) {
                                    Text(
                                        "Load older posts",
                                        color = KaspaTeal,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.loadMoreFeed(tab, manual = true) }
                                            .padding(vertical = 12.dp),
                                    )
                                } else if (feedPaging.isLoadingMore) {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = KaspaTeal)
                                    }
                                }
                            }
                        }
                    } else {
                        // Endless scroll, per tab. Each tab keeps its own cursor in the view
                        // model, so a swipe away and back resumes exactly where it was.
                        EndlessScroll(listState = feedListStates[page], key = tab) {
                            viewModel.loadMoreFeed(tab)
                        }
                        NewPostsPill(
                            count = pendingNew.size,
                            onClick = {
                                viewModel.showPendingNewPosts(tab)
                                newPostsScope.launch { feedListStates[page].animateScrollToItem(0) }
                            }
                        )
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
                                    // The reply bubble opens the Reply composer; the card opens
                                    // the thread (iOS feed cells).
                                    onReply = { replyComposerTarget = post },
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clickable { openThread(post) }
                                            .padding(start = 68.dp, top = 2.dp, bottom = 8.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.PlaylistAdd,
                                            contentDescription = null,
                                            tint = KaspaTeal,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("View thread", color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                                HorizontalDivider(
                                    color = colors.surfaceVariant,
                                    modifier = Modifier.padding(start = 68.dp),
                                )
                            }
                            pagingFooter(feedPaging, keySuffix = "feed-$page") {
                                viewModel.loadMoreFeed(tab, manual = true)
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

            // Bottom toast stack: undo countdown above the on-chain confirmation (iOS pads 84).
            KaPostsToastLayer(
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
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
            initialText = restoredComposerText,
            initialThreadSegments = restoredComposerSegments,
            onDismiss = {
                showComposer = false
                restoredComposerText = ""
                restoredComposerSegments = emptyList()
            },
            onSubmit = { text ->
                showComposer = false
                restoredComposerText = ""
                restoredComposerSegments = emptyList()
                viewModel.schedulePost(text)
            },
            viewModel = viewModel,
            onSubmitThread = { segments ->
                showComposer = false
                restoredComposerText = ""
                restoredComposerSegments = emptyList()
                viewModel.scheduleThread(segments)
            },
            onSaveDraft = { draftText, segments ->
                KaPostDraftStore.save(draftContext, myAddressForDrafts.orEmpty(), null, draftText, segments)
                reloadDrafts()
            },
        )
    }

    // Reopening a draft, as whatever it was written as. A reply or quote draft keeps only the
    // txid of the post it was about (never a stale copy of someone else's post), so that post
    // is resolved here - memory first, then the indexer, then the chain - and handed to the
    // composer (iOS draftComposer). Posting it removes it; re-saving updates it in place.
    editingDraft?.let { draft ->
        val sourceId = draft.replyRemoteId?.takeIf { it.isNotEmpty() } ?: draft.quotedRemoteId?.takeIf { it.isNotEmpty() }
        LaunchedEffect(draft.id, sourceId) {
            draftSourcePost = null
            if (sourceId != null) draftSourcePost = viewModel.resolveAnyPost(sourceId)
        }
        val isReplyDraft = !draft.replyRemoteId.isNullOrEmpty()
        val isQuoteDraft = !isReplyDraft && !draft.quotedRemoteId.isNullOrEmpty()
        val source = draftSourcePost
        KaPostComposerDialog(
            title = when {
                isReplyDraft -> "Reply to Post"
                isQuoteDraft && source != null -> "Quote Post"
                else -> "New Post"
            },
            quoted = source,
            quotedDisplayName = source?.let { viewModel.posterDisplayName(it.posterAddress) } ?: "",
            quotedAvatarUrl = source?.let { viewModel.senderProfiles.value[it.posterAddress] },
            isReply = isReplyDraft,
            submitLabel = if (isReplyDraft) "Reply" else null,
            onDismiss = { editingDraft = null; draftSourcePost = null },
            onSubmit = { text ->
                KaPostDraftStore.delete(draftContext, myAddressForDrafts.orEmpty(), draft.id)
                editingDraft = null
                when {
                    source != null && isReplyDraft -> viewModel.submitReply(source, text)
                    source != null && isQuoteDraft -> viewModel.scheduleQuote(source, text)
                    // The post it referred to could not be resolved: post the text rather than
                    // discard what was written.
                    else -> viewModel.schedulePost(text)
                }
                draftSourcePost = null
            },
            viewModel = viewModel,
            // A reply or a quote is one post about one other, so it never stacks into a thread.
            onSubmitThread = if (isReplyDraft || isQuoteDraft) null else { segments ->
                KaPostDraftStore.delete(draftContext, myAddressForDrafts.orEmpty(), draft.id)
                editingDraft = null
                viewModel.scheduleThread(segments)
            },
            editingDraftId = draft.id,
            initialText = draft.text,
            initialThreadSegments = draft.threadSegments,
            onSaveDraft = { draftText, segments ->
                KaPostDraftStore.save(
                    draftContext, myAddressForDrafts.orEmpty(), draft.id, draftText, segments,
                    replyRemoteId = draft.replyRemoteId,
                    quotedRemoteId = draft.quotedRemoteId,
                )
                reloadDrafts()
            },
        )
    }

    if (showDrafts) {
        KaPostsOverlayScaffold(title = "Drafts", onClose = { showDrafts = false }) {
            if (drafts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Edit, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No drafts", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Close the composer with something written and you'll be offered a draft.",
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            } else {
                LazyColumn {
                    items(drafts, key = { it.id }) { draft ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDrafts = false
                                    editingDraft = draft
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    draft.preview,
                                    color = LocalAppColors.current.textPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // When it was saved, and how many posts it stacks (iOS row).
                                    remember(draft.savedAt, draft.segmentCount) {
                                        relativePostTime(draft.savedAt) +
                                            (if (draft.segmentCount > 1) " · ${draft.segmentCount} posts" else "")
                                    },
                                    color = LocalAppColors.current.textSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete draft",
                                tint = LocalAppColors.current.textSecondary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        KaPostDraftStore.delete(draftContext, myAddressForDrafts.orEmpty(), draft.id)
                                        reloadDrafts()
                                    },
                            )
                        }
                        HorizontalDivider(color = LocalAppColors.current.surfaceVariant)
                    }
                }
            }
        }
    }

    replyComposerTarget?.let { target ->
        KaPostComposerDialog(
            title = "Reply to Post",
            // The post being answered renders under the editor - the same card a quote shows.
            quoted = target,
            quotedDisplayName = viewModel.posterDisplayName(target.posterAddress),
            quotedAvatarUrl = viewModel.senderProfiles.value[target.posterAddress],
            isReply = true,
            submitLabel = "Reply",
            initialText = restoredComposerText,
            onDismiss = { replyComposerTarget = null; restoredComposerText = "" },
            onSubmit = { text ->
                replyComposerTarget = null
                restoredComposerText = ""
                viewModel.submitReply(target, text)
            },
            viewModel = viewModel,
            // A reply draft remembers WHAT it answers, so reopening it brings the post back.
            onSaveDraft = { draftText, segments ->
                KaPostDraftStore.save(
                    draftContext, myAddressForDrafts.orEmpty(), null, draftText, segments,
                    replyRemoteId = target.remoteId,
                )
                reloadDrafts()
            },
        )
    }

    quoteTarget?.let { target ->
        KaPostComposerDialog(
            title = "Quote Post",
            quoted = target,
            quotedDisplayName = viewModel.posterDisplayName(target.posterAddress),
            quotedAvatarUrl = viewModel.senderProfiles.value[target.posterAddress],
            initialText = restoredComposerText,
            onDismiss = { quoteTarget = null; restoredComposerText = "" },
            onSubmit = { text ->
                quoteTarget = null
                restoredComposerText = ""
                viewModel.scheduleQuote(target, text)
            },
            // A quote is never a thread, so no segments to carry here.
            viewModel = viewModel,
            // A quote draft remembers WHAT it quotes, so reopening it brings the post back (iOS).
            onSaveDraft = { draftText, segments ->
                KaPostDraftStore.save(
                    draftContext, myAddressForDrafts.orEmpty(), null, draftText, segments,
                    quotedRemoteId = target.remoteId,
                )
                reloadDrafts()
            },
        )
    }

    // Thread stack - the topmost id renders; back pops. The overlay resolves the id against the
    // live post tree itself (it must recompose as replies land), so only the id is handed over.
    val fetchedAncestorChains by viewModel.fetchedAncestors.collectAsState()
    threadStack.lastOrNull()?.let { topId ->
        val topPost = viewModel.findPost(topId)
        // The chain get-thread returns, which is every level above this post - exact whether
        // you tapped down to it, opened it from a profile, or landed on it from a link. The
        // navigation stack plus an in-memory parent walk is the fallback for the moment before
        // that fetch answers (and for a local post that has no txid yet). Memoised: the walk runs
        // findParent - a recursive search of every loaded list - several times, and this screen
        // recomposes far more often than the inputs change (iOS AncestorChainMemo).
        val ancestors = remember(topPost, threadStack, fetchedAncestorChains) {
            topPost?.remoteId
                ?.let { fetchedAncestorChains[it] }
                ?.takeIf { it.isNotEmpty() }
                ?: topPost?.let { ancestorsFromMemory(viewModel, it, threadStack) }
                ?: emptyList()
        }
        KaPostThreadOverlay(
            postId = topId,
            viewModel = viewModel,
            ancestors = ancestors,
            onJumpToAncestor = { ancestor -> jumpToAncestor(ancestor) },
            onReplyToComment = { target -> replyComposerTarget = target },
            onClose = { closeTopThread() },
            onOpenNested = { nested -> openThread(nested) },
            onOpenProfile = { address, pubkey -> viewModel.openPosterProfile(address, pubkey) },
            onOpenShared = { txId -> openShared(txId) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            focusReplyRemoteId = threadFocusReplyId,
            onFocusReplyHandled = { threadFocusReplyId = null },
            replyText = threadReplyText,
            onReplyTextChange = { threadReplyText = it },
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
            onReply = { replyComposerTarget = it },
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
            onReply = { replyComposerTarget = it },
        )
    }

    tipTarget?.let { (tipAddress, tipName) ->
        KaPostTipDialog(
            address = tipAddress,
            displayName = tipName,
            onDismiss = { tipTarget = null },
        )
    }

    if (showSearch) {
        KaPostsSearchOverlay(
            viewModel = viewModel,
            onClose = { showSearch = false },
            onOpenPost = { txId ->
                showSearch = false
                // The same route a shared link takes: a search result can be older than the
                // loaded feed, and openShared is what knows how to go and find one.
                openShared(txId)
            },
            onOpenProfile = { address ->
                showSearch = false
                viewModel.openPosterProfile(address, null)
            },
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
            // Bookmarks can reply; it has no thread surface of its own, so the card tap stays
            // inert and the reply bubble opens the Reply composer (iOS).
            onReply = { replyComposerTarget = it },
            // Post Activity and the repost icon were silent no-ops in bookmarks (the cell's
            // defaults); the overlays they raise are Dialog windows, which stack above the
            // bookmarks Dialog, so they open in place.
            onViewEngagement = { engagementTarget = it },
            onRepostTap = { repostHandler(it) },
            onTip = { tipTarget = it.posterAddress to viewModel.posterDisplayName(it.posterAddress) },
        )
    }
}

/**
 * The chain above [post] when get-thread has not answered: the navigation stack (only trusted
 * when its tail really is this post's parent - a deep link can land mid-thread with a stack that
 * drifted) plus an upward walk through the loaded tree, at most eight hops, with a cycle guard.
 * Mirrors iOS ancestorChain.
 */
private fun ancestorsFromMemory(
    viewModel: KaPostsViewModel,
    post: KaPostDraft,
    threadStack: List<String>,
): List<KaPostDraft> {
    var walked = threadStack.dropLast(1).mapNotNull { viewModel.findPost(it) }
    val last = walked.lastOrNull()
    if (last != null &&
        viewModel.findParent(post.id)?.id != last.id &&
        last.comments.none { it.id == post.id }
    ) {
        walked = emptyList()
    }
    val above = mutableListOf<KaPostDraft>()
    var cursor: KaPostDraft? = walked.firstOrNull() ?: post
    var hops = 0
    while (cursor != null && hops < 8) {
        val parent = viewModel.findParent(cursor.id) ?: break
        if (above.any { it.id == parent.id } || walked.any { it.id == parent.id }) break
        above.add(parent)
        cursor = parent
        hops++
    }
    return above.reversed() + walked
}

// MARK: - Side menu


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
internal fun ConnectionDotButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
    val haptics = LocalView.current
    // iOS feedTabButton: accent bold when selected, accent at half opacity otherwise, a
    // full-width 2pt accent underline under the selected tab.
    Row(modifier = modifier.fillMaxWidth()) {
        KaPostsFeedTabs.forEach { tab ->
            val label = tab.label()
            val isSelected = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        haptics.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        onSelect(tab)
                    }
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) KaspaTeal else KaspaTeal.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isSelected) KaspaTeal else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun FeedEmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    icon: ImageVector? = null,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(52.dp))
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, color = colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
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
    val contactPhoto by viewModel.contactPhotos.collectSelectedAsState(post.posterAddress) { it[post.posterAddress] }
    val name = posterDisplayNameState(viewModel, post.posterAddress)
    val isFollowingPoster by viewModel.following.collectSelectedAsState(post.posterAddress) { post.posterAddress in it }
    val hapticView = LocalView.current
    fun lightHaptic() { hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
    val cellDeadlines by viewModel.undoDeadlines.collectSelectedAsState(post.id) {
        Triple(it["repost:${post.id}"], it["like:${post.id}"], it["dislike:${post.id}"])
    }
    var showOverflow by remember { mutableStateOf(false) }
    // The edit composer is opened from the cell itself, so editing works wherever a post is
    // shown: the feed, a thread, your profile, bookmarks.
    var editing by remember { mutableStateOf(false) }
    // Tapped link awaiting the Copy/Open choice (iOS parity: links never auto-open).
    var tappedLinkUrl by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val isMine = post.posterAddress == viewModel.myAddress()

    // On-device translation, X-style. Per-post slices like everything else in this cell, so one
    // reader translating one post never re-renders the viewport. Language identification is async,
    // so the cell asks once and the affordance appears when the answer arrives.
    val translationKey = remember(post.remoteId, post.id) { viewModel.translationKey(post) }
    // One collector for the three translation slices rather than three.
    //
    // collectSelectedAsState builds a map+distinctUntilChanged flow and collects it, so every
    // call is a coroutine per cell. A fling recycles rows constantly, and at eight-ish collectors
    // a row that is a lot of launches and cancellations per second - which is felt as the list
    // not keeping up rather than as any one thing being slow. Combined into a single flow, the
    // per-row precision is unchanged: distinctUntilChanged on the triple still only emits when
    // THIS post's translation state moves.
    val translationSlice by remember(
        viewModel.translations, viewModel.showingOriginal, viewModel.translatable, translationKey
    ) {
        combine(
            viewModel.translations,
            viewModel.showingOriginal,
            viewModel.translatable,
        ) { translations, originals, translatable ->
            KaPostTranslationSlice(
                state = translations[translationKey],
                showingOriginal = translationKey in originals,
                canTranslate = translationKey in translatable,
            )
        }.distinctUntilChanged()
    }.collectAsState(
        initial = KaPostTranslationSlice(
            state = viewModel.translations.value[translationKey],
            showingOriginal = translationKey in viewModel.showingOriginal.value,
            canTranslate = translationKey in viewModel.translatable.value,
        )
    )
    val translationState = translationSlice.state
    val showingOriginal = translationSlice.showingOriginal
    val canTranslate = translationSlice.canTranslate
    LaunchedEffect(translationKey) { viewModel.considerTranslation(post) }
    val bodyText = viewModel.displayText(post, translationState, showingOriginal)

    // Measured on what is actually rendered, so a translation that runs longer than its original
    // still folds. Same numbers as iOS's KaPostCellView.isLongPost.
    val isLongPost = bodyText.length > 280 || bodyText.count { it == '\n' } >= 8
    // Show more expands the post IN PLACE. It used to open the thread, so the only way to read a
    // long post in a feed was to leave the feed - and on an ancestor it did nothing useful at all.
    // Opening the post is what tapping the post itself is for.
    var textExpanded by remember(post.id) { mutableStateOf(false) }
    val foldText = truncatesLongText && isLongPost && !textExpanded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Dimmed while a delete waits out its undo countdown: the post is on its way out,
            // and Undo is right there.
            .alpha(if (post.pendingDeletion) 0.4f else 1f)
            .clickable(enabled = !isRoot) { onOpenThread() }
            // iOS KaPostCellView: 16pt horizontal, 12pt vertical, 40pt avatar. The thread line
            // drawn behind ancestor cells depends on exactly these numbers.
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.clickable { onOpenProfile() }) {
                ContactAvatar(
                    imageUrl = avatarUrl,
                    fallbackText = name,
                    size = 40.dp,
                    // A saved contact's own photo overrides the KNS avatar, as everywhere else.
                    deviceContactPhotoUri = contactPhoto?.deviceContactPhotoUri,
                    backupPhotoBase64 = contactPhoto?.backupPhotoBase64,
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
                        modifier = Modifier.weight(1f, fill = false),
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
                    // A post whose text was changed says so, as on iOS.
                    if (post.editedAt != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("· edited", color = colors.textSecondary, fontSize = 13.sp)
                    }
                    if (!isMine) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val isFollowing = isFollowingPoster
                        Text(
                            text = if (isFollowing) "Following" else "Follow",
                            color = if (isFollowing) colors.textSecondary else KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                lightHaptic()
                                viewModel.toggleFollow(post.posterAddress, post.posterPubkey)
                            },
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = "More",
                        tint = colors.textSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { showOverflow = true },
                    )
                    if (editing) {
                        // Edit mode: no threading and no draft prompt - this is a change to
                        // something already posted, not a new one being written.
                        KaPostComposerDialog(
                            title = "Edit Post",
                            quoted = null,
                            submitLabel = "Save",
                            onDismiss = { editing = false },
                            onSubmit = { newText ->
                                editing = false
                                viewModel.editPost(post, newText)
                            },
                            viewModel = viewModel,
                            initialText = post.text,
                        )
                    }
                    // The three-dots menu is a half sheet, like every other menu in the app,
                    // so each option can say what it does; the popup had room for a verb and
                    // nothing else. Share lives in the action row itself, next to bookmark,
                    // matching iOS - no duplicate entry here.
                    if (showOverflow) {
                        ActionSheetContainer(
                            title = "Post",
                            subtitle = null,
                            onDismiss = { showOverflow = false },
                        ) {
                            if (post.remoteId != null) {
                                ActionSheetRow(
                                    icon = Icons.Outlined.BarChart,
                                    title = "Post Activity",
                                    subtitle = "Who liked, disliked, reposted and quoted this post.",
                                ) {
                                    showOverflow = false
                                    onViewEngagement()
                                }
                            }
                            // Editable only while the window is open, and only our own: the
                            // indexer enforces the same rules, so anything else would be
                            // written to the chain and then ignored.
                            val editLeftMs = if (isMine) post.editTimeRemainingMs else null
                            if (editLeftMs != null) {
                                ActionSheetRow(
                                    icon = Icons.Default.Edit,
                                    title = "Edit",
                                    subtitle = "Change the text. ${editWindowLeftText(editLeftMs)} left.",
                                ) {
                                    showOverflow = false
                                    editing = true
                                }
                            }
                            // Any age, unlike an edit: what you posted is yours to withdraw.
                            if (isMine && post.remoteId != null && post.deliveryStatus == KaPostDraft.Delivery.SENT) {
                                ActionSheetRow(
                                    icon = Icons.Default.Delete,
                                    title = "Delete",
                                    subtitle = "Takes it out of every feed. The chain keeps the transaction.",
                                    tint = Color(0xFFFF3B30),
                                ) {
                                    showOverflow = false
                                    viewModel.deletePost(post)
                                }
                            }
                            if (!isMine) {
                                // Named, as on iOS: "Mute alice" says who this lands on.
                                ActionSheetRow(
                                    icon = Icons.Default.VolumeOff,
                                    title = "Mute $name",
                                    subtitle = "Hides their posts everywhere. They can still interact with you.",
                                ) {
                                    showOverflow = false
                                    viewModel.mute(post.posterAddress)
                                }
                                ActionSheetRow(
                                    icon = Icons.Default.Block,
                                    title = "Block $name",
                                    subtitle = "Hides their posts and stops them interacting with you.",
                                    tint = Color(0xFFFF3B30),
                                ) {
                                    showOverflow = false
                                    viewModel.block(post.posterAddress)
                                }
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
                val postAnnotated = remember(bodyText) { annotatedPostText(bodyText) }
                val postBody = @Composable {
                    androidx.compose.foundation.text.ClickableText(
                        text = postAnnotated,
                        // The post a thread is FOCUSED on reads larger than the posts around it,
                        // the way X sizes the one you opened against its ancestors and replies.
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = if (isRoot) 18.sp else 15.sp,
                            lineHeight = if (isRoot) 24.sp else 20.sp,
                        ),
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
                }
                // Long-press selects text only in the post you OPENED. Making every cell
                // selectable turned a long-press anywhere in a feed into a selection handle
                // fight, and scrolling past a post is far more common than quoting one; the
                // focused post is the one you came to read. Matches iOS's selectableText(isRoot).
                if (isRoot) {
                    androidx.compose.foundation.text.selection.SelectionContainer { postBody() }
                } else {
                    postBody()
                }
                // Half sheet rather than an alert dialog - see LinkActionsSheet.
                tappedLinkUrl?.let { url ->
                    LinkActionsSheet(
                        url = url,
                        onDismiss = { tappedLinkUrl = null },
                        onOpen = {
                            // A KaChat link goes straight to the post or room it names, rather
                            // than out to the browser and back - same as the chat screens.
                            val internal = KaChatLink.parse(url)
                            if (internal != null) openKaChatLink(internal) else uriHandler.openUri(url)
                        },
                        onCopy = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                if (truncatesLongText && isLongPost) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (textExpanded) "Show less" else "Show more",
                        color = KaspaTeal,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { textExpanded = !textExpanded },
                    )
                }
                TranslateAffordance(
                    state = translationState,
                    canTranslate = canTranslate,
                    showingOriginal = showingOriginal,
                    onTranslate = { viewModel.translatePost(post) },
                    onShowOriginal = { viewModel.showOriginal(post) },
                    onShowTranslation = { viewModel.showTranslation(post) },
                )
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
                Spacer(modifier = Modifier.height(8.dp))
                EngagementRow(
                    post = post,
                    commentCount = viewModel.commentCount(post),
                    repostDeadline = cellDeadlines.first,
                    likeDeadline = cellDeadlines.second,
                    dislikeDeadline = cellDeadlines.third,
                    // The bubble replies (feed cells open the Reply composer; the card opens the
                    // thread). The focused post of a thread has no bubble at all - the box under
                    // it is its reply (iOS threadCell isRoot: onComment nil).
                    onComment = if (isRoot) null else (onReply ?: onOpenThread),
                    onRepost = onRepostTap,
                    onLike = { lightHaptic(); viewModel.toggleLike(post) },
                    onDislike = { lightHaptic(); viewModel.toggleDislike(post) },
                    onBookmark = { lightHaptic(); viewModel.toggleBookmark(post) },
                    onCancelCountdown = { lightHaptic(); viewModel.cancelUndoable(it) },
                    onShare = {
                        viewModel.shareText(post)?.let { text ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Post"))
                        }
                    },
                    onTip = if (!isMine) onTip?.let { tip -> { lightHaptic(); tip() } } else null,
                    // X-style repost sheet; quote routes through the VM's quoteRequest flow so
                    // the main screen's composer opens from any cell. "Undo Repost" submits the
                    // unquote counter-action rather than a second repost.
                    onRepostConfirm = {
                        if (post.repostedByMe) viewModel.scheduleUnrepost(post) else viewModel.scheduleRepost(post)
                    },
                    onQuote = { viewModel.requestQuote(post) },
                    onRetry = { lightHaptic(); viewModel.retryPost(post) },
                    onHaptic = { lightHaptic() },
                )
            }
        }
    }
}

/**
 * A quoted post's text as the reader should see it: bold, italic, underline, strikethrough and
 * subtext kept, links inert and unstyled - the card is itself tappable through to the quoted
 * post, and a live link inside it would compete with that (iOS markdownPreview).
 */
private fun markdownPreview(source: String): androidx.compose.ui.text.AnnotatedString {
    val rendered = KaPostsMarkdown.render(source)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(rendered.text)
        for (span in rendered.spans) {
            if (span.start >= span.end || span.end > rendered.text.length) continue
            val style = span.style
            val decorations = buildList {
                if (style.underline) add(androidx.compose.ui.text.style.TextDecoration.Underline)
                if (style.strikethrough) add(androidx.compose.ui.text.style.TextDecoration.LineThrough)
            }
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontWeight = if (style.bold) FontWeight.Bold else null,
                    fontStyle = if (style.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    fontSize = if (style.subtext) 11.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    color = if (style.subtext) Color(0xFF8A8A8E) else Color.Unspecified,
                    textDecoration = if (decorations.isEmpty()) null
                    else androidx.compose.ui.text.style.TextDecoration.combine(decorations),
                ),
                span.start,
                span.end,
            )
        }
    }
}

@Composable
private fun QuotedEmbedCard(
    quoted: KaPostDraft.QuotedRef,
    displayName: String,
    avatarUrl: String? = null,
    /** 20 inside a cell's embed, 22 under the composer's editor (iOS). */
    avatarSize: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.textSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        // iOS parity: small avatar + bold name + relative time header row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(
                imageUrl = avatarUrl,
                fallbackText = displayName,
                size = avatarSize,
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
            // Styled markdown preview, links inert. A plain repost has no text and renders as
            // an empty body, as on iOS.
            text = remember(quoted.text) { markdownPreview(quoted.text) },
            color = colors.textPrimary,
            fontSize = 14.sp,
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
    /** Null hides the bubble entirely (the focused post of a thread). */
    onComment: (() -> Unit)?,
    onRepost: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onBookmark: () -> Unit,
    onCancelCountdown: (String) -> Unit,
    // Share sits in the action row itself, as on iOS, rather than only behind the overflow menu.
    onShare: (() -> Unit)? = null,
    onTip: (() -> Unit)? = null,
    // X-style repost menu: when BOTH are set (and the post is on-chain), tapping repost opens
    // a compact anchored two-row menu (Repost / Quote) instead of the old dialog.
    onRepostConfirm: (() -> Unit)? = null,
    onQuote: (() -> Unit)? = null,
    /** Re-submits a post that failed to reach the network (the red Retry at the row's end). */
    onRetry: (() -> Unit)? = null,
    onHaptic: () -> Unit = {},
) {
    val colors = LocalAppColors.current
    // iOS order and colours: comment, like (red), dislike (orange), repost (accent), bookmark,
    // share, Tip, then the delivery state at the trailing edge. Fixed 18pt gaps rather than
    // evenly spread, so the row reads the same on every width.
    val likeRed = Color(0xFFFF3B30)
    val dislikeOrange = Color(0xFFFF9500)
    var likeBurst by remember(post.id) { mutableStateOf(false) }
    // The Kaspa-logo burst plays when the like actually lands - after the countdown fires, not
    // on the tap that armed it (iOS runLikeBurst).
    var previousLiked by remember(post.id) { mutableStateOf(post.likedByMe) }
    LaunchedEffect(post.likedByMe) {
        if (post.likedByMe && !previousLiked) {
            likeBurst = true
            delay(750)
            likeBurst = false
        }
        previousLiked = post.likedByMe
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (onComment != null) {
            EngagementAction(
                countdownKey = null,
                deadline = null,
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp)) },
                count = commentCount,
                onTap = { onHaptic(); onComment() },
                onCancel = onCancelCountdown,
            )
        }
        Box {
            EngagementAction(
                countdownKey = "like:${post.id}",
                deadline = likeDeadline,
                icon = {
                    Icon(
                        if (post.likedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                        tint = if (post.likedByMe) likeRed else colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                count = post.likes,
                countTint = if (post.likedByMe) likeRed else null,
                onTap = onLike,
                onCancel = onCancelCountdown,
            )
            LikeBurst(visible = likeBurst, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
        }
        EngagementAction(
            countdownKey = "dislike:${post.id}",
            deadline = dislikeDeadline,
            icon = {
                Icon(
                    if (post.dislikedByMe) Icons.Outlined.ThumbDown else Icons.Outlined.ThumbDownOffAlt, null,
                    tint = if (post.dislikedByMe) dislikeOrange else colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            },
            count = post.dislikes,
            countTint = if (post.dislikedByMe) dislikeOrange else null,
            onTap = onDislike,
            onCancel = onCancelCountdown,
        )
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
                countTint = if (post.repostedByMe) KaspaTeal else null,
                onTap = {
                    onHaptic()
                    // X-style: a compact menu floating over the tapped button.
                    if (onRepostConfirm != null && onQuote != null && post.remoteId != null) {
                        repostMenuOpen = true
                    } else {
                        onRepost()
                    }
                },
                onCancel = onCancelCountdown,
            )
            // Half sheet rather than a dropdown, so the two choices get room to say what they
            // do - "Repost" and "Quote" as bare words are only obvious once you already know
            // the difference. See RepostActionsSheet.
            if (repostMenuOpen) {
                RepostActionsSheet(
                    isReposted = post.repostedByMe,
                    onDismiss = { repostMenuOpen = false },
                    onRepost = { onRepostConfirm?.invoke() },
                    onQuote = { onQuote?.invoke() },
                )
            }
        }
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
        // Share: only for posts that exist on chain, matching iOS, which hides it until the
        // post has a remote id to link to.
        if (onShare != null && post.remoteId != null) {
            EngagementAction(
                countdownKey = null,
                deadline = null,
                icon = {
                    Icon(
                        Icons.Default.Share, null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                count = null,
                onTap = { onHaptic(); onShare() },
                onCancel = onCancelCountdown,
            )
        }
        if (onTip != null) {
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
                Spacer(modifier = Modifier.width(3.dp))
                Text("Tip", color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        // Bottom-right: on-chain delivery state, mirroring chat bubbles - green check once the K
        // transaction is on the network (for a minute), spinner while submitting, red Retry when
        // it didn't go through (iOS).
        DeliveryState(post = post, onRetry = onRetry)
    }
}

@Composable
private fun DeliveryState(post: KaPostDraft, onRetry: (() -> Unit)?) {
    val colors = LocalAppColors.current
    when (post.deliveryStatus) {
        KaPostDraft.Delivery.SENT -> {
            if (post.remoteId == null) return
            // An edit's transaction landing restarts the minute, so a change to an old post
            // gets its own check rather than none at all.
            val sentReference = post.sentAt ?: post.timestamp
            val age = System.currentTimeMillis() - sentReference
            var expired by remember(sentReference) { mutableStateOf(age >= 60_000L) }
            if (!expired) {
                LaunchedEffect(sentReference) {
                    val remaining = 60_000L - (System.currentTimeMillis() - sentReference)
                    if (remaining > 0) delay(remaining)
                    expired = true
                }
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Posted",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        KaPostDraft.Delivery.PENDING -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = colors.textSecondary,
        )
        KaPostDraft.Delivery.FAILED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = onRetry != null) { onRetry?.invoke() }
                .padding(2.dp),
        ) {
            Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(14.dp))
            Text("Retry", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/**
 * The Kaspa logo popping in with a spring, spinning once and dissolving back down to the heart -
 * iOS runLikeBurst's keyframes, on Animatables.
 */
@Composable
private fun LikeBurst(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    val scale = remember { androidx.compose.animation.core.Animatable(0.2f) }
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(1.5f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f))
        }
        launch { alpha.animateTo(1f, androidx.compose.animation.core.tween(150)) }
        launch { rotation.animateTo(360f, androidx.compose.animation.core.tween(550)) }
        delay(550)
        launch { alpha.animateTo(0f, androidx.compose.animation.core.tween(180)) }
        scale.animateTo(0.5f, androidx.compose.animation.core.tween(180))
    }
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo),
        contentDescription = null,
        modifier = modifier
            .size(18.dp)
            .scale(scale.value)
            .rotate(rotation.value)
            .alpha(alpha.value),
    )
}

@Composable
private fun EngagementAction(
    countdownKey: String?,
    deadline: Long?,
    icon: @Composable () -> Unit,
    count: Int?,
    onTap: () -> Unit,
    onCancel: (String) -> Unit,
    /** The count takes the icon's active colour (iOS tints the whole button). */
    countTint: Color? = null,
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
            UndoBadge()
        } else {
            icon()
        }
        if (count != null && count > 0) {
            Spacer(modifier = Modifier.width(5.dp))
            // Raw integers, as on iOS - no "1.2K".
            Text(
                text = "$count",
                color = countTint ?: colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Takes an icon's place while its undo window runs. Tap = cancel.
 *
 * Deliberately NO seconds and no ring. The ticking number belongs in the undo toast, which is the
 * one place it can be read without hunting for the row it came from; on the icon it turned a
 * static engagement row into digits counting down, and cost every pending cell a recomposition
 * ten times a second to do it. The row just says "you can still take this back".
 */
@Composable
private fun UndoBadge() {
    // iOS countdownButton: the undo arrow inside an orange-outlined capsule.
    val orange = Color(0xFFFF9500)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .border(1.dp, orange.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
            tint = orange,
            modifier = Modifier.size(13.dp),
        )
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
    // The IME inset is read as a key so the reveal re-runs while the keyboard ANIMATES in, not
    // just once when it is already up. Without this the effect could compute its target against
    // a viewport that was still shrinking and land short.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(value.selection, value.text.length, layout, scroll.viewportSize, scroll.maxValue, imeBottom) {
        val result = layout ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        // Writing at the very end - which is what composing a post IS - always pins the view to
        // the bottom. This does not depend on the caret rect, the text height or the padding
        // arithmetic below being fresh in the same frame, which is the fragile part when several
        // of those settle across different frames while the keyboard is opening.
        if (value.selection.collapsed && value.selection.end >= value.text.length) {
            if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
            return@LaunchedEffect
        }
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
    /** Overrides the submit button's wording. A reply says "Reply", not "Post". */
    submitLabel: String? = null,
    /** The card under the editor is the post being ANSWERED, not quoted: "Post your reply". */
    isReply: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    /** Enables @mention autocomplete (chips of 1:1 KNS-domain contacts) when provided. */
    viewModel: KaPostsViewModel? = null,
    /** Enables X-style thread posting (+ stacks segments; Post All submits the chain). */
    onSubmitThread: ((List<String>) -> Unit)? = null,
    /** Set when opened from a saved draft, so re-saving updates it rather than piling up copies. */
    editingDraftId: String? = null,
    initialText: String = "",
    initialThreadSegments: List<String> = emptyList(),
    /** Called with the composer's contents when the user chooses Save Draft on close. */
    onSaveDraft: ((String, List<String>) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    // TextFieldValue rather than String: the caret offset is what [KeepCaretVisible] below needs
    // to scroll the editor to, and the String overload never exposes it.
    var text by remember { mutableStateOf(TextFieldValue(initialText)) }
    var threadSegments by remember { mutableStateOf(initialThreadSegments) }
    // Closing with something written used to throw it away silently, which is the whole reason
    // drafts exist.
    var showCloseOptions by remember { mutableStateOf(false) }
    val limit = KaPostDraft.POST_CHARACTER_LIMIT
    // Counted in grapheme clusters, as iOS counts Characters: an emoji is one, not two.
    val charCount = remember(text.text) { graphemeCount(text.text) }
    val totalSegments = threadSegments.size + (if (text.text.isNotBlank()) 1 else 0)
    val canPost = totalSegments > 0 && charCount <= limit
    val threadingEnabled = onSubmitThread != null && quoted == null
    val editorFocus = remember { FocusRequester() }
    var editorFocused by remember { mutableStateOf(false) }
    val hapticView = LocalView.current
    val showFeeEstimate by (viewModel?.showFeeEstimate?.collectAsState() ?: remember { mutableStateOf(false) })

    /** Anything written is worth asking about (iOS hasUnsavedContent). */
    fun requestClose() {
        val hasContent = (listOf(text.text) + threadSegments).any { it.isNotBlank() }
        if (hasContent && onSaveDraft != null) showCloseOptions = true else onDismiss()
    }

    // Warm the KNS caches so typing @ has domains to offer.
    LaunchedEffect(Unit) { viewModel?.prefetchMentionCandidates() }
    // The editor is what this screen is for: it takes focus as it appears (iOS isFocused = true).
    LaunchedEffect(Unit) { runCatching { editorFocus.requestFocus() } }
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
    // Keyed on the KNS name map too, so a contact whose domain resolves after the @ was typed
    // appears without another keystroke (iOS observes KNSService).
    val knsNames by (viewModel?.senderKnsNames?.collectAsState() ?: remember { mutableStateOf(emptyMap<String, String?>()) })
    val mentionSuggestions = remember(text.text, resolvedAnyDomain, knsNames) {
        val query = mentionQuery
        if (query == null || viewModel == null) emptyList()
        else {
            val contacts = viewModel.mentionCandidates()
                .map { it.first }
                .filter { query.isEmpty() || it.startsWith(query) }
                .sorted()
            val extra = resolvedAnyDomain
            if (extra != null && extra !in contacts && (query.isEmpty() || extra.startsWith(query))) {
                contacts + extra
            } else contacts
        }
    }

    Dialog(
        // System back goes through the same "Save this post?" question as the X - a dismiss that
        // throws written text away is the one thing the drafts flow exists to prevent.
        onDismissRequest = { requestClose() },
        properties = KaPostsFullScreenDialogProperties,
    ) {
        ForceFullScreenDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(KaPostsOverlayInsets),
        ) {
            if (showCloseOptions) {
                ActionSheetContainer(
                    title = "Save this post?",
                    subtitle = null,
                    onDismiss = { showCloseOptions = false },
                ) {
                    ActionSheetRow(
                        icon = Icons.Default.Save,
                        title = "Save Draft",
                        subtitle = "Keep it in Drafts to finish later.",
                    ) {
                        showCloseOptions = false
                        onSaveDraft?.invoke(text.text, threadSegments)
                        onDismiss()
                    }
                    ActionSheetRow(
                        icon = Icons.Default.Delete,
                        title = "Discard",
                        subtitle = "Throw this away.",
                        tint = Color(0xFFFF3B30),
                    ) {
                        showCloseOptions = false
                        onDismiss()
                    }
                }
            }
            // Same dot/balance row every other KaPosts overlay carries, above the X. A post
            // costs KAS, so this is the one screen where the balance is not just reassurance,
            // and the composer covers the feed that would otherwise be showing both.
            KaPostsOverlayStatusBar()
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
                        .clickable { requestClose() }
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
                KaPostCharacterMeter(count = charCount)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    if (totalSegments > 1) "Post All ($totalSegments)" else (submitLabel ?: "Post"),
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
                KaPostMentionSuggestionList(
                    suggestions = mentionSuggestions,
                    onPick = { domain ->
                        val replaced = text.text.replace(MENTION_REPLACE_REGEX, "@$domain ")
                        // Caret to the end of the inserted mention, otherwise it would
                        // snap back to offset 0 and the editor would scroll to the top.
                        text = TextFieldValue(replaced, TextRange(replaced.length))
                    },
                )
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
                    // A modest box at the top that grows with what you write, matching iOS's
                    // 120pt floor - NOT the whole screen. weight(1f) here made the editor eat
                    // every remaining pixel: an empty composer was one enormous empty box, and
                    // when quoting it pushed the quoted post off the bottom edge so you could
                    // not see what you were replying to. Capped so a long post scrolls inside
                    // the box rather than walking the toolbar off the screen.
                    // iOS: a 120pt floor that grows with the text, whatever is under it (a quoted
                    // or answered post included); capped so a long post scrolls inside the box.
                    .heightIn(min = 120.dp, max = if (quoted != null) 220.dp else 300.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, colors.textSecondary.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(editorScroll),
                ) {
                    BasicTextField(
                        value = text,
                        // Hard cap at the limit, X-style: a paste that overflows is clipped to
                        // the first 25,000 characters rather than dropped (iOS prefix).
                        onValueChange = { text = clampToCharacterLimit(it, limit) },
                        onTextLayout = { editorLayout = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(KaspaTeal),
                        keyboardOptions = KeyboardOptions(autoCorrect = false),
                        modifier = Modifier
                            .fillMaxWidth()
                            // Short drafts still fill the whole card, so a tap anywhere inside
                            // the border lands in the field. A CONSTANT, deliberately, not the
                            // box's measured height: the box now sizes to its content, so
                            // feeding its height back in as the content's minimum would ratchet
                            // it up a frame at a time until it hit the cap. 120dp box minus its
                            // 12dp padding top and bottom.
                            .heightIn(min = 96.dp)
                            .padding(12.dp)
                            .focusRequester(editorFocus)
                            .onFocusChanged { editorFocused = it.isFocused },
                        decorationBox = { inner ->
                            if (text.text.isEmpty()) {
                                Text(
                                    when {
                                        isReply -> "Post your reply"
                                        quoted != null -> "Add a comment"
                                        threadSegments.isEmpty() -> "What's happening on Kaspa?"
                                        else -> "Add another post"
                                    },
                                    color = colors.textSecondary,
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
            // Below the field, not floating inside it. As an overlay it covered the bottom-right
            // of the text area, so a post long enough to reach that corner ran underneath it -
            // the one place in the composer where your own words could be hidden.
            if (threadingEnabled && text.text.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(KaspaTeal.copy(alpha = 0.15f))
                            .clickable {
                                threadSegments = threadSegments + text.text.trim()
                                text = TextFieldValue("")
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = KaspaTeal,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Add to thread", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            // Live network-fee estimate while typing (Settings > Show Fee Estimate), matching
            // the chat composer's behaviour (iOS feeEstimateRow).
            val trimmedForFee = text.text.trim()
            if (showFeeEstimate && trimmedForFee.isNotEmpty() && viewModel != null) {
                val fee = remember(trimmedForFee) { viewModel.estimatePostFeeSompi(trimmedForFee) }
                Text(
                    "Est. fee: ${"%.8f".format(java.util.Locale.US, fee / 100_000_000.0)} KAS",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
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
                        avatarSize = 22.dp,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            // Last in the column, so it rides directly on top of the keyboard, where a formatting
            // bar belongs - and only while the editor has focus, as on iOS: a permanent row of
            // eight icons with nothing to act on is clutter.
            if (editorFocused) {
                MarkdownFormattingToolbar { action ->
                    hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    val edit = KaPostsMarkdown.apply(
                        action,
                        text.text,
                        text.selection.min,
                        text.selection.max,
                    )
                    text = clampToCharacterLimit(
                        TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd)),
                        limit,
                    )
                }
            }
        }
    }
}

/** Characters as a reader counts them: grapheme clusters, the way iOS's `String.count` does. */
private fun graphemeCount(text: String): Int {
    if (text.isEmpty()) return 0
    // Pure ASCII cannot contain a multi-unit cluster, so skip the iterator for the common case.
    if (text.all { it.code < 0x80 }) return text.length
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(text)
    var count = 0
    while (iterator.next() != java.text.BreakIterator.DONE) count++
    return count
}

/** Clips [value] to its first [limit] grapheme clusters, keeping the caret inside the text. */
private fun clampToCharacterLimit(value: TextFieldValue, limit: Int): TextFieldValue {
    val text = value.text
    if (text.length <= limit) return value
    if (graphemeCount(text) <= limit) return value
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(text)
    var count = 0
    var end = 0
    while (count < limit) {
        val next = iterator.next()
        if (next == java.text.BreakIterator.DONE) { end = text.length; break }
        end = next
        count++
    }
    val clipped = text.substring(0, end)
    return TextFieldValue(
        clipped,
        TextRange(value.selection.start.coerceAtMost(clipped.length), value.selection.end.coerceAtMost(clipped.length)),
    )
}

/**
 * The @mention list above an editor: the KNS domains of everyone you've chatted with plus a
 * live-resolved any-KNS match. Short lists hug their content; longer ones scroll at a fixed
 * height (~4.5 rows, so it visibly reads as scrollable). iOS KaPostMentionSuggestionBar.
 */
@Composable
private fun KaPostMentionSuggestionList(
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 280.dp)
            .then(if (suggestions.size > 4) Modifier.height(168.dp) else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.textPrimary.copy(alpha = 0.06f))
            .border(1.dp, colors.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()),
    ) {
        suggestions.forEachIndexed { index, domain ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(domain) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text("@", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(domain, color = colors.textPrimary, fontSize = 14.sp)
            }
            if (index != suggestions.lastIndex) {
                HorizontalDivider(color = colors.surfaceVariant)
            }
        }
    }
}

/**
 * The formatting buttons above the keyboard.
 *
 * Exists because the syntax is only discoverable if you already know it: someone who has never
 * typed `~~` cannot guess that it means strikethrough. Every button toggles, and every one of them
 * writes the same markers a person could have typed by hand, so the two ways of formatting a post
 * produce identical text.
 */
@Composable
private fun MarkdownFormattingToolbar(onAction: (KaPostsMarkdown.ToolbarAction) -> Unit) {
    val colors = LocalAppColors.current
    // Order runs from the formatting people reach for most to the least.
    val items = listOf(
        Triple(KaPostsMarkdown.ToolbarAction.BOLD, Icons.Default.FormatBold, "Bold"),
        Triple(KaPostsMarkdown.ToolbarAction.ITALIC, Icons.Default.FormatItalic, "Italic"),
        Triple(KaPostsMarkdown.ToolbarAction.UNDERLINE, Icons.Default.FormatUnderlined, "Underline"),
        Triple(KaPostsMarkdown.ToolbarAction.STRIKETHROUGH, Icons.Default.StrikethroughS, "Strikethrough"),
        Triple(KaPostsMarkdown.ToolbarAction.BULLET_LIST, Icons.Default.FormatListBulleted, "Bulleted list"),
        Triple(KaPostsMarkdown.ToolbarAction.NUMBERED_LIST, Icons.Default.FormatListNumbered, "Numbered list"),
        Triple(KaPostsMarkdown.ToolbarAction.SUBTEXT, Icons.Default.FormatSize, "Small text"),
        Triple(KaPostsMarkdown.ToolbarAction.LINK, Icons.Default.Link, "Link"),
    )
    HorizontalDivider(color = colors.textPrimary.copy(alpha = 0.08f))
    // Scrolls rather than squeezing: eight buttons at a comfortable tap size do not fit a narrow
    // phone, and shrinking them to fit would make them hard to hit.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (action, icon, label) ->
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction(action) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(19.dp),
                )
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
    // Invisible rather than absent while empty, so the header does not shift when the first
    // character is typed (iOS keeps the slot with opacity 0).
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (count == 0) 0f else 1f)) {
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

// MARK: - Thread overlay (ancestors + root + conversation + comments, X-style)

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
 *
 * Layout mirrors iOS's thread sheet: the chain above (full cells joined by a thread line), the
 * focused post, the author's continuation ("Thread") or an unbranched exchange ("Conversation"),
 * a "Comments (N)" header, the comments with one level of inline expansion, and a small reply box
 * pinned under it all.
 */
@Composable
fun KaPostThreadOverlay(
    postId: String,
    /** The chain above this post, oldest first - see the call site. */
    ancestors: List<KaPostDraft> = emptyList(),
    onJumpToAncestor: (KaPostDraft) -> Unit = {},
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    onOpenNested: (KaPostDraft) -> Unit,
    onOpenProfile: (String, String?) -> Unit,
    onOpenShared: (String) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
    /**
     * Answering a SPECIFIC post in this thread - an ancestor above it, or one of the replies
     * below. Opens the "Reply to Post" composer with that post under the editor, as on iOS and
     * desktop. The inline box at the bottom stays for answering the post you opened.
     */
    onReplyToComment: (KaPostDraft) -> Unit = {},
    /** RemoteId of a reply to scroll to once it lands (reply landings open the PARENT's thread
     *  and hand the reply's txid through here). */
    focusReplyRemoteId: String? = null,
    onFocusReplyHandled: () -> Unit = {},
    /** The reply box's text, hoisted so it survives walking up and down the thread (iOS). */
    replyText: TextFieldValue = TextFieldValue(""),
    onReplyTextChange: (TextFieldValue) -> Unit = {},
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
    // Deleting the post this thread is about leaves nothing to read here, so the thread closes
    // itself rather than sitting on the "couldn't find it" state (iOS closeThread).
    var everResolved by remember(postId) { mutableStateOf(postState != null) }
    LaunchedEffect(post) {
        if (post != null) everResolved = true else if (everResolved) onClose()
    }
    // System back closes this level of the thread, matching the Back button in the header.
    // Nested pushes each get their own overlay instance, so back walks the thread stack down
    // one level at a time.
    BackHandler(onBack = onClose)
    if (post == null) {
        // An id this view cannot resolve (the feed refresh dropped it, the network never
        // answered): say so rather than showing a blank screen (iOS).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .pointerInput(Unit) { detectHorizontalDragGestures { _, _ -> } },
        ) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(KaPostsOverlayInsets)) {
                KaPostsOverlayStatusBar()
                KaPostsOverlayHeader(title = "Post", closeLabel = "Back", onClose = onClose)
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Edit, null, tint = colors.textSecondary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("This post could not be loaded", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "It may have been removed, or the network may be unreachable.",
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
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
    // "Thread" is what an author calls their OWN continuation. When the chain carries other
    // people's replies it is a conversation, and calling that a thread would credit them to
    // the root author (iOS isSelfThread).
    val isSelfThread = remember(threadChain, post.posterAddress) { threadChain.all { it.posterAddress == post.posterAddress } }

    LaunchedEffect(post.remoteId) {
        viewModel.loadReplies(post)
        viewModel.loadSelfThreadChain(post)
        // The real chain above this post, not just the levels tapped through to reach it.
        viewModel.loadAncestors(post)
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

    // Scroll-to-the-reply for reply landings. Re-keys on the comment list until the focused
    // reply is actually among the loaded comments (page one may still be in flight), then
    // scrolls once and clears the focus so nothing re-scrolls later. Index math mirrors the
    // LazyColumn below: ancestors, root-context, root, the optional chain section (header +
    // segments + divider), the comments header, then the comments.
    if (focusReplyRemoteId != null) {
        LaunchedEffect(visibleComments, threadChain, ancestors.size, focusReplyRemoteId) {
            val commentIndex = visibleComments.indexOfFirst { it.remoteId == focusReplyRemoteId }
            if (commentIndex >= 0) {
                val chainItems = if (threadChain.isEmpty()) 0 else threadChain.size + 2
                val target = ancestors.size + 2 + chainItems + 1 + commentIndex
                delay(500)
                runCatching { threadListState.animateScrollToItem(target) }
                onFocusReplyHandled()
            }
        }
    }

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
            KaPostsOverlayStatusBar()
            // ONE control, and it always means back: up a level while the thread has history,
            // out of it at the root (iOS).
            KaPostsOverlayHeader(title = "Post", closeLabel = "Back", onClose = onClose)
            LazyColumn(state = threadListState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The chain above this post, oldest first, each rung tappable to jump straight
                // to that level - X stacks these over the focal post. Full post cells, not
                // summaries: the parents ARE posts, so they behave like posts and the screen
                // reads as one conversation. Long ones truncate so the post you actually opened
                // still owns the screen.
                items(ancestors, key = { "ancestor-${it.id}" }) { ancestor ->
                    // X's thread line, and no divider: a rule between two posts separates them,
                    // while the line running down the avatar column from one into the next is
                    // what makes the chain read as one conversation. Drawn over the cell rather
                    // than beside it, so the post keeps the same full width as every other cell.
                    // The geometry follows the cell's own: 16dp leading plus a 40dp avatar puts
                    // its centre at 36, and 12dp top padding plus that avatar ends it at 52.
                    Box {
                        KaPostCell(
                            post = ancestor,
                            viewModel = viewModel,
                            onOpenThread = { onJumpToAncestor(ancestor) },
                            onRepostTap = { onRepostTap(ancestor) },
                            onOpenProfile = { onOpenProfile(ancestor.posterAddress, ancestor.posterPubkey) },
                            onOpenQuoted = onOpenShared,
                            onViewEngagement = { onViewEngagement(ancestor) },
                            truncatesLongText = true,
                            onReply = { onReplyToComment(ancestor) },
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .padding(start = 35.dp, top = 52.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(colors.textSecondary.copy(alpha = 0.3f)),
                            )
                        }
                    }
                }
                item(key = "root-context") {
                    // The step ABOVE whatever the chain could reach: this post is a reply to
                    // something we have not loaded, so the txid is all there is to offer.
                    if (ancestors.isEmpty()) {
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
                        // The focused post has no reply bubble: the box under the thread is its
                        // reply (iOS threadCell isRoot).
                        isRoot = true,
                    )
                    HorizontalDivider(color = colors.surfaceVariant)
                }
                // X-style thread reading: the author's own continuation, connected and ordered.
                if (threadChain.isNotEmpty()) {
                    item(key = "thread-chain-header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                                tint = KaspaTeal,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${if (isSelfThread) "Thread" else "Conversation"} · ${threadChain.size + 1} posts",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                            )
                        }
                    }
                    items(threadChain, key = { "chain-${it.id}" }) { segment ->
                        LaunchedEffect(segment.posterAddress) {
                            viewModel.ensureSenderProfileFetched(segment.posterAddress)
                        }
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(1.dp))
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
                                    onReply = { onReplyToComment(segment) },
                                )
                            }
                        }
                    }
                    item(key = "thread-chain-divider") {
                        HorizontalDivider(color = colors.surfaceVariant)
                    }
                }
                item(key = "comments-header") {
                    Text(
                        if (visibleComments.isEmpty()) "Comments" else "Comments (${visibleComments.size})",
                        color = colors.textSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                if (visibleComments.isEmpty()) {
                    // An unbranched exchange is rendered in full ABOVE, and its members are
                    // filtered out of this list - so "no comments yet" would contradict the
                    // replies the reader can already see (iOS).
                    item(key = "no-comments") {
                        Text(
                            if (threadChain.isEmpty()) "No comments yet - be the first to reply."
                            else "Every reply is in the conversation above.",
                            color = colors.textSecondary,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                        )
                    }
                }
                items(visibleComments, key = { it.id }) { comment ->
                    LaunchedEffect(comment.posterAddress) {
                        viewModel.ensureSenderProfileFetched(comment.posterAddress)
                    }
                    ThreadCommentNode(
                        comment = comment,
                        expanded = comment.id in expandedIds,
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
                        // A specific reply is answered in the composer, where it renders under the
                        // editor - not by silently re-aiming the box at the bottom of the screen.
                        onReplyTo = { target -> onReplyToComment(target) },
                    )
                    HorizontalDivider(
                        color = colors.surfaceVariant,
                        modifier = Modifier.padding(start = 68.dp),
                    )
                }
                pagingFooter(threadPaging, keySuffix = "thread") {
                    viewModel.loadMoreReplies(post, manual = true)
                }
            }
            HorizontalDivider(color = colors.surfaceVariant)
            // X's shape: a small reply box directly under the post you opened, with the replies
            // above it. Answering a SPECIFIC reply still opens the composer (see the comment
            // cells' onReply), where that reply renders under the editor.
            //
            // While the funding gate is active the reply row renders dimmed and any tap on it
            // opens the funding card instead of focusing the field — same "no composer until
            // funded" rule as the New Post button.
            ThreadReplyComposer(
                viewModel = viewModel,
                text = replyText,
                onTextChange = onReplyTextChange,
                fundingGateActive = fundingGate.active,
                onShowFundingGate = { showFundingGate = true },
                onSubmit = { text -> viewModel.submitReply(post, text) },
            )
        }
        // The main screen's toast layer sits BEHIND this opaque overlay — without a copy in
        // here, a like/repost/reply made from an open thread showed no undo toast and no
        // network confirmation, which read as the buttons doing nothing at all for the whole
        // 5-second undo window (and made Undo unreachable). Its flows are collected inside
        // KaPostsToastLayer's own restart scope so toast emissions never recompose the overlay.
        KaPostsToastLayer(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
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
 * The thread's pinned reply box, in its OWN restart scope so a keystroke recomposes only this
 * composable and not every visible comment above it.
 *
 * iOS's shape: the mention list above, a field of about four lines that then scrolls internally,
 * the character meter, and an arrow-up send button - no formatting toolbar, the composer has that.
 * The box always targets the opened post.
 */
@Composable
private fun ThreadReplyComposer(
    viewModel: KaPostsViewModel,
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    fundingGateActive: Boolean,
    onShowFundingGate: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val replyText = text.text
    val charCount = remember(replyText) { graphemeCount(replyText) }
    val canSend = replyText.isNotBlank()
    val hapticView = LocalView.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (fundingGateActive) 0.45f else 1f),
        ) {
            // @mention autocomplete for COMMENTS - identical machinery to the post composer:
            // KNS domains of everyone you've chatted with, plus a live any-KNS resolve of the
            // typed query. Shown above the input so the keyboard can never hide it.
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
            val knsNames by viewModel.senderKnsNames.collectAsState()
            val replyMentionSuggestions = remember(replyText, replyResolvedAnyDomain, knsNames) {
                val query = replyMentionQuery
                if (query == null) emptyList()
                else {
                    val contacts = viewModel.mentionCandidates()
                        .map { it.first }
                        .filter { query.isEmpty() || it.startsWith(query) }
                        .sorted()
                    val extra = replyResolvedAnyDomain
                    if (extra != null && extra !in contacts && (query.isEmpty() || extra.startsWith(query))) {
                        contacts + extra
                    } else contacts
                }
            }
            if (replyMentionSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                KaPostMentionSuggestionList(
                    suggestions = replyMentionSuggestions,
                    onPick = { domain ->
                        // The completion lands at the caret: the token being completed is the
                        // one at the end of the text, so the caret goes to the end with it.
                        val completed = replyText.replace(MENTION_REPLACE_REGEX, "@$domain ")
                        onTextChange(TextFieldValue(completed, TextRange(completed.length)))
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicTextField(
                    value = text,
                    // Hard cap at the limit, X-style - clipped, never dropped (iOS prefix).
                    onValueChange = { onTextChange(clampToCharacterLimit(it, KaPostDraft.POST_CHARACTER_LIMIT)) },
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(KaspaTeal),
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    // Roughly four lines: past that the field scrolls instead of pushing the post
                    // you are answering off the screen (iOS maxHeight 92).
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        // A fixed radius, not a capsule: a capsule's corners are half its height,
                        // so a grown multi-line field curves into its own text.
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.textSecondary.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    decorationBox = { inner ->
                        if (replyText.isEmpty()) {
                            Text("Post your reply", color = colors.textSecondary, fontSize = 15.sp)
                        }
                        inner()
                    },
                )
                KaPostCharacterMeter(count = charCount)
                Icon(
                    Icons.Default.ArrowCircleUp,
                    contentDescription = "Reply",
                    tint = if (canSend) KaspaTeal else colors.textSecondary,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(enabled = canSend) {
                            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            val trimmed = replyText.trim()
                            onTextChange(TextFieldValue(""))
                            focusManager.clearFocus()
                            onSubmit(trimmed)
                        },
                )
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
 * One comment with X-style inline expansion: "View N replies" loads its direct replies and draws
 * them under it with a connector down the avatar column. Exactly ONE inline level, as on iOS -
 * deeper conversation is reached by tapping a reply, which opens it as its own thread root.
 */
@Composable
private fun ThreadCommentNode(
    comment: KaPostDraft,
    expanded: Boolean,
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
    val replyCount = viewModel.commentCount(comment)

    Column {
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
        if (replyCount > 0) {
            if (expanded) {
                val replies = comment.comments.filter { it.posterAddress !in hidden }
                val page = pagingStateOf(
                    viewModel,
                    comment.remoteId?.let { KaPostsViewModel.pageThread(it) } ?: "thread:none",
                )
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    // Connector dropping from the comment's avatar column.
                    Box(
                        modifier = Modifier
                            .padding(start = 35.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(colors.textSecondary.copy(alpha = 0.3f)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        if (replies.isEmpty()) {
                            // Emptiness alone used to mean "loading", so a comment whose reply
                            // count is a SUBTREE count while get-replies returns its direct
                            // replies (none) span forever. The page state knows the difference
                            // between still fetching, failed, and answered with nothing.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                            ) {
                                when {
                                    page.isLoadingMore || !viewModel.repliesRequested(comment) -> {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.textSecondary)
                                        Text("Loading replies...", color = colors.textSecondary, fontSize = 12.sp)
                                    }
                                    page.error != null -> {
                                        Text(page.error, color = colors.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        Text(
                                            "Retry",
                                            color = KaspaTeal,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            modifier = Modifier.clickable { viewModel.loadReplies(comment, force = true) },
                                        )
                                    }
                                    else -> Text("No replies", color = colors.textSecondary, fontSize = 12.sp)
                                }
                            }
                        } else {
                            replies.forEach { reply ->
                                LaunchedEffect(reply.posterAddress) {
                                    viewModel.ensureSenderProfileFetched(reply.posterAddress)
                                }
                                KaPostCell(
                                    post = reply,
                                    viewModel = viewModel,
                                    onOpenThread = { onOpenNested(reply) },
                                    onRepostTap = { onRepostTap(reply) },
                                    onOpenProfile = { onOpenProfile(reply.posterAddress, reply.posterPubkey) },
                                    onOpenQuoted = onOpenShared,
                                    onViewEngagement = { onViewEngagement(reply) },
                                    onReply = { onReplyTo(reply) },
                                )
                            }
                            // Inline chains page endlessly too (long comment threads). Not its
                            // own scroll container, so the next page loads on tap.
                            if (page.isLoadingMore || page.error != null || page.stalled) {
                                PagingFooterContent(state = page) { viewModel.loadMoreReplies(comment, manual = true) }
                            } else if (page.hasMore) {
                                Text(
                                    "Load more",
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.loadMoreReplies(comment, manual = true) }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
                ThreadToggleButton("Hide replies") { onToggleExpand(comment.id) }
            } else {
                ThreadToggleButton(if (replyCount == 1) "View 1 reply" else "View $replyCount replies") {
                    viewModel.expandReplies(comment)
                    onToggleExpand(comment.id)
                }
            }
        }
    }
}

/** The "View N replies" / "Hide replies" row: a short rule then the caption, in accent (iOS). */
@Composable
private fun ThreadToggleButton(title: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clickable { onClick() }
            .padding(start = 36.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.textSecondary.copy(alpha = 0.3f)),
        )
        Text(title, color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// MARK: - Profile overlay (mine + tapped poster)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    /** The reply bubble on a profile row opens the Reply composer (iOS). */
    onReply: ((KaPostDraft) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    // Per-address slices for THIS profile's chrome (see collectSelectedAsState): the rows
    // below trigger a KNS fetch per author as they scroll in, and collecting the whole maps
    // here meant every one of those results recomposed the entire overlay - banner, header
    // and both pager pages - mid-scroll.
    val profileAvatarUrl by viewModel.senderProfiles.collectSelectedAsState(address) { it[address] }
    val profileBannerUrl by viewModel.senderBanners.collectSelectedAsState(address) { it[address] }
    val profileBio by viewModel.senderBios.collectSelectedAsState(address) { it[address] }
    val profilePhoto by viewModel.contactPhotos.collectSelectedAsState(address) { it[address] }
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
    // Follow is gated on the ADDRESS, not the caller's flag: your own address opened as a
    // poster (from search, say) must not offer to follow yourself (iOS).
    val isOwnAddress = address == viewModel.myAddress()
    val hapticView = LocalView.current

    var selectedTab by remember { mutableStateOf(0) } // 0 = Posts, 1 = Replies
    val name = posterDisplayNameState(viewModel, address)
    // One list for the whole page, header included, rather than a pager holding two. The banner,
    // bio and tab row are the list's first item, so they scroll up out of the way while reading
    // instead of holding most of a phone screen for a name already read. The cost is the
    // horizontal Posts/Replies swipe: a pager needs its pages to own their scrolling, which is
    // exactly what puts the header outside them.
    val profileListState = rememberLazyListState()
    // Posts and Replies are separate paging surfaces (separate endpoints, separate cursors), so
    // each pager page carries its own load-more trigger and footer.
    val profilePubkey = remember(isMine, pubkey, posterProfile?.pubkey) { viewModel.profilePubkey(isMine) }

    LaunchedEffect(address) { viewModel.ensureSenderProfileFetched(address) }

    Dialog(
        onDismissRequest = onClose,
        properties = KaPostsFullScreenDialogProperties,
    ) {
        // Same window-sizing fix as the other overlays.
        ForceFullScreenDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(KaPostsOverlayInsets),
        ) {
            KaPostsOverlayStatusBar()
            // Inline "Profile" title with a trailing Back, the banner starting BELOW the bar (iOS).
            KaPostsOverlayHeader(title = "Profile", closeLabel = "Back", onClose = onClose)
            val repliesPage = selectedTab == 1
            val pageItems = if (repliesPage) repliesList else myPostsList
            val pagePaging = pagingStateOf(
                viewModel,
                profilePubkey?.let { KaPostsViewModel.pageProfile(it, isMine, repliesPage) } ?: "profile:none",
            )
            EndlessScroll(listState = profileListState, key = selectedTab to profilePubkey) {
                viewModel.loadMoreProfile(isMine, replies = repliesPage)
            }
            // Pull-to-refresh reloads page one of the tab being looked at (iOS .refreshable).
            val pullRefreshState = rememberPullToRefreshState()
            LaunchedEffect(pullRefreshState.isRefreshing) {
                if (pullRefreshState.isRefreshing) {
                    viewModel.refreshProfileTab(isMine, replies = repliesPage)
                    pullRefreshState.endRefresh()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .nestedScroll(pullRefreshState.nestedScrollConnection),
            ) {
            LazyColumn(
                state = profileListState,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "profile-header") {
                    Column {
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
                        // Avatar overlapping the banner, X-style, with a 3dp ring in the page
                        // background so it reads as sitting on top of the banner. The slot is
                        // only as tall as the part below the banner (82 - 38), so nothing under
                        // it has to be offset back up.
                        Box(modifier = Modifier.padding(start = 16.dp).height(44.dp)) {
                            Box(
                                modifier = Modifier
                                    .offset(y = (-38).dp)
                                    .size(82.dp)
                                    .clip(CircleShape)
                                    .background(colors.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                ContactAvatar(
                                    imageUrl = profileAvatarUrl,
                                    fallbackText = name,
                                    size = 76.dp,
                                    deviceContactPhotoUri = profilePhoto?.deviceContactPhotoUri,
                                    backupPhotoBase64 = profilePhoto?.backupPhotoBase64,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Name with the actions in a single row: name - Follow - Chat. Your
                            // own profile carries the name alone (iOS).
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    name,
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (!isMine) {
                                    Spacer(Modifier.weight(1f))
                                    if (!isOwnAddress) {
                                        val isFollowing = address in following
                                        Button(
                                            onClick = {
                                                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                                viewModel.toggleFollow(address, pubkey)
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isFollowing) colors.textSecondary.copy(alpha = 0.35f) else KaspaTeal,
                                                contentColor = if (isFollowing) colors.textPrimary else Color.Black,
                                            ),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp),
                                        ) {
                                            Text(if (isFollowing) "Following" else "Follow", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                            viewModel.ensureContactExists(address) { contactId ->
                                                onClose()
                                                navController.navigate("chat/$contactId")
                                            }
                                        },
                                        border = androidx.compose.foundation.BorderStroke(1.dp, KaspaTeal.copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KaspaTeal),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Chat", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                val followingCount = if (isMine) following.size else posterProfile?.followingCount ?: 0
                                val followersCount = if (isMine) (myFollowersCount ?: 0) else posterProfile?.followersCount ?: 0
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clickable(enabled = onOpenFollowList != null) { onOpenFollowList?.invoke(false) },
                                ) {
                                    Text("$followingCount", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Following", color = colors.textSecondary, fontSize = 15.sp)
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clickable(enabled = onOpenFollowList != null) { onOpenFollowList?.invoke(true) },
                                ) {
                                    Text("$followersCount", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Followers", color = colors.textSecondary, fontSize = 15.sp)
                                }
                            }
                            profileBio?.takeIf { it.isNotBlank() }?.let { bio ->
                                Box(Modifier.padding(top = 2.dp)) { ExpandableBioText(bio) }
                            }
                            if (isMine) {
                                // Everything above this - avatar, banner, name, bio - comes from your KNS
                                // profile, so the way to change any of it belongs here rather than only on
                                // the Profile tab. Same destination that tab's own Edit KNS Profile uses.
                                Row(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(KaspaTeal.copy(alpha = 0.15f))
                                        .clickable {
                                            onClose()
                                            navController.navigate("edit_kns_profile")
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Badge, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Edit KNS Profile", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                        HorizontalDivider(color = colors.surfaceVariant)
                        // iOS profileFeedTabBar: accent bold / accent at half, full-width 2.5dp underline.
                        Row(modifier = Modifier.fillMaxWidth()) {
                            listOf("Posts", "Replies").forEachIndexed { index, label ->
                                val isSelected = index == selectedTab
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                            selectedTab = index
                                        }
                                        .padding(top = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        label,
                                        color = if (isSelected) KaspaTeal else KaspaTeal.copy(alpha = 0.5f),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(2.5.dp)
                                            .background(if (isSelected) KaspaTeal else Color.Transparent),
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = colors.surfaceVariant)
                    }
                }
                val items = pageItems
                if (items.isEmpty()) {
                    item(key = "empty") {
                        val loading = if (isMine) isLoadingMyProfile else posterProfile?.isLoading == true
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (loading) {
                                CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            } else {
                                // Your own profile says whose posts these would be; another
                                // person's carries the title alone (iOS).
                                Icon(
                                    if (repliesPage) Icons.Outlined.ChatBubbleOutline else Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(44.dp),
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    if (repliesPage) "No replies yet" else "No posts yet",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (isMine) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        if (repliesPage) "Replies you post will show up here." else "Your posts will show up here.",
                                        color = colors.textSecondary,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                    )
                                }
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
                            onReply = onReply?.let { reply -> { reply(post) } },
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
                    pagingFooter(pagePaging, keySuffix = "profile-$selectedTab") {
                        viewModel.loadMoreProfile(isMine, replies = repliesPage, manual = true)
                    }
                }
            }
            if (pullRefreshState.verticalOffset > 0f || pullRefreshState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            }
        }
    }
}

// MARK: - Notifications overlay

/**
 * The half sheet behind a tapped KaPosts notification. Same shape as the app's other half-sheet
 * menus, so a menu is a menu wherever it appears. Mirrors iOS's `notificationActionsSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KaPostNotificationActionsSheet(
    title: String,
    canOpenInApp: Boolean,
    onOpenInApp: () -> Unit,
    onOpenExplorer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    // Back closes the sheet, not the list behind it - the innermost handler wins, so the
    // overlay's own back is left alone while this is up.
    BackHandler(enabled = true) { onDismiss() }
    Box(modifier = Modifier.fillMaxSize()) {
        // The scrim. Tapping it, like tapping outside a sheet anywhere else, puts it away.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(colors.background)
                // Swallows taps so they cannot fall through to the scrim behind it.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The drag handle a sheet is recognised by, even though this one is dismissed by
                // tapping away rather than dragged.
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.textSecondary.copy(alpha = 0.4f)),
                )
                Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (canOpenInApp) {
                    ActionSheetRow(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "Open in KaPosts",
                        subtitle = "Goes to the post this is about, in the app.",
                        onClick = onOpenInApp,
                    )
                }
                ActionSheetRow(
                    icon = Icons.Default.Public,
                    title = "View in Explorer",
                    subtitle = "Opens the transaction on your chosen block explorer.",
                    onClick = onOpenExplorer,
                )
            }
        }
    }
}

/**
 * Search across KaPosts: posts by their text, and the people who wrote them.
 *
 * The depth line under the results is not decoration. This search is client-side (the K indexer
 * has no search endpoint), so it can only see as far back as it has paged - saying how far, and
 * offering to go further, is the difference between "no results" and "no results yet".
 */
@Composable
fun KaPostsSearchOverlay(
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    var showPeople by remember { mutableStateOf(false) }
    val postResults by viewModel.searchPostResults.collectAsState()
    val peopleResults by viewModel.searchPeopleResults.collectAsState()
    val scannedCount by viewModel.searchScannedCount.collectAsState()
    val hasMore by viewModel.searchHasMore.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val loadFailed by viewModel.searchLoadFailed.collectAsState()

    LaunchedEffect(query) { viewModel.setSearchQuery(query) }
    // One page up front so the first search has something to answer with.
    LaunchedEffect(Unit) { if (scannedCount == 0) viewModel.searchLoadMore() }

    // The one KaPosts screen that says Done rather than Back (iOS).
    KaPostsOverlayScaffold(title = "Search", onClose = onClose, closeLabel = "Done") {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Posts and people", color = colors.textSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = colors.textSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = KaspaTeal,
                    unfocusedBorderColor = colors.textSecondary,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TabRow(
                selectedTabIndex = if (showPeople) 1 else 0,
                containerColor = colors.background,
                contentColor = KaspaTeal,
            ) {
                Tab(selected = !showPeople, onClick = { showPeople = false }) {
                    Text("Posts", color = if (!showPeople) KaspaTeal else colors.textSecondary, modifier = Modifier.padding(12.dp))
                }
                Tab(selected = showPeople, onClick = { showPeople = true }) {
                    Text("People", color = if (showPeople) KaspaTeal else colors.textSecondary, modifier = Modifier.padding(12.dp))
                }
            }

            if (query.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Search, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Search KaPosts", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Find posts by what they say, and people by their name or domain. Only people who have posted appear.",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                return@KaPostsOverlayScaffold
            }

            val empty = if (showPeople) peopleResults.isEmpty() else postResults.isEmpty()
            if (empty && !isSearching) {
                // iOS emptyResults: what was read, and an offer to keep looking.
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Nothing found yet", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (hasMore) "Searched the most recent $scannedCount posts. Older ones have not been read yet."
                        else "Searched every post available.",
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (hasMore) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.searchLoadMore() },
                            enabled = !isSearching,
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                        ) {
                            Text("Keep looking", color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                return@KaPostsOverlayScaffold
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (showPeople) {
                    items(peopleResults, key = { it.address }) { person ->
                        LaunchedEffect(person.address) { viewModel.ensureSenderProfileFetched(person.address) }
                        val personAvatar by viewModel.senderProfiles
                            .collectSelectedAsState(person.address) { it[person.address] }
                        val personName = posterDisplayNameState(viewModel, person.address)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenProfile(person.address); onClose() }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ContactAvatar(imageUrl = personAvatar, fallbackText = personName, size = 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    personName,
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                )
                                Text(
                                    if (person.postCount == 1) "1 post found" else "${person.postCount} posts found",
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = colors.textSecondary)
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                } else {
                    items(postResults, key = { it.id }) { post ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { post.remoteId?.let { onOpenPost(it) }; onClose() }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                posterDisplayNameState(viewModel, post.posterAddress),
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                post.text,
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                remember(post.timestamp) { relativePostTime(post.timestamp) },
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                }

                // Says how deep the search has gone, and offers to go deeper. Under the results
                // rather than only when empty: a handful of hits does not mean there are no
                // more (iOS depthFooter).
                item {
                    if (hasMore) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isSearching) { viewModel.searchLoadMore() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(color = colors.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                if (isSearching) "Reading older posts" else "Search older posts",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text("$scannedCount read", color = colors.textSecondary, fontSize = 11.sp)
                        }
                    } else if (loadFailed) {
                        Text(
                            "Could not read any further just now.",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    // The notification whose action sheet is up. Tapping a row asks what to do with it rather
    // than committing to one of the two answers.
    var actionTarget by remember { mutableStateOf<KaPostsViewModel.NotificationItem?>(null) }

    LaunchedEffect(Unit) { viewModel.loadNotifications() }
    EndlessScroll(listState = listState) { viewModel.loadMoreNotifications() }
    // Pull-to-refresh reloads page one (iOS .refreshable on both the list and the empty state).
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.loadNotifications()
            pullRefreshState.endRefresh()
        }
    }

    KaPostsOverlayScaffold(title = "Notifications", onClose = onClose) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection),
        ) {
        if (isLoading && items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KaspaTeal)
            }
        } else if (items.isEmpty()) {
            // Wrapped in a LazyColumn purely so the empty state can be pulled to refresh too.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Column(
                        modifier = Modifier.fillParentMaxSize().padding(horizontal = 40.dp),
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
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    LaunchedEffect(item.actorAddress) {
                        viewModel.ensureSenderProfileFetched(item.actorAddress)
                    }
                    // Per-actor slices: one avatar/name landing repaints its own row only.
                    val actorAvatar by viewModel.senderProfiles
                        .collectSelectedAsState(item.actorAddress) { it[item.actorAddress] }
                    val actorPhoto by viewModel.contactPhotos
                        .collectSelectedAsState(item.actorAddress) { it[item.actorAddress] }
                    val actorName = posterDisplayNameState(viewModel, item.actorAddress)
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The whole row asks what to do with this notification. There used to
                            // be a "View" button wired straight to the explorer sitting next to a
                            // row tap that opened the post in-app - two destinations, one of them
                            // unlabelled, and the button quietly ate taps meant for the row.
                            .clickable { actionTarget = item }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        // The avatar carries a small badge saying what KIND of action this is,
                        // bottom-trailing on a page-background disc (iOS itemRow).
                        Box {
                            ContactAvatar(
                                imageUrl = actorAvatar,
                                fallbackText = actorName,
                                size = 38.dp,
                                deviceContactPhotoUri = actorPhoto?.deviceContactPhotoUri,
                                backupPhotoBase64 = actorPhoto?.backupPhotoBase64,
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 4.dp, y = 4.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(colors.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    notificationKindIcon(item.kind),
                                    contentDescription = null,
                                    tint = notificationKindTint(item.kind, colors.textSecondary),
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = androidx.compose.ui.text.buildAnnotatedString {
                                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(actorName) }
                                    append(" ")
                                    append(notificationActionText(item.kind))
                                },
                                color = colors.textPrimary,
                                fontSize = 15.sp,
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
                        Icon(
                            Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                }
                pagingFooter(paging, keySuffix = "notifications") { viewModel.loadMoreNotifications(manual = true) }
            }
        }
        if (pullRefreshState.verticalOffset > 0f || pullRefreshState.isRefreshing) {
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
        // Drawn here, inside the overlay's own window: this list is a full-screen dialog, and a
        // sheet asking for a window of its own would come up behind it - which is how tapping a
        // notification came to do nothing at all.
        actionTarget?.let { item ->
            KaPostNotificationActionsSheet(
                title = posterDisplayNameState(viewModel, item.actorAddress),
                canOpenInApp = item.targetTxId != null,
                onOpenInApp = {
                    val target = item.targetTxId
                    actionTarget = null
                    target?.let(onOpenPost)
                },
                onOpenExplorer = {
                    actionTarget = null
                    uriHandler.openUri(kaspaExplorer.txUrl(item.id))
                },
                onDismiss = { actionTarget = null },
            )
        }
        }
    }
}

/** "1h 12m" / "8m" / "40s" - how long is left to edit, said the way a countdown is read. */
private fun editWindowLeftText(remainingMs: Long): String {
    val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}

/** iOS Item.Kind.icon: the glyph stamped on the actor's avatar. */
private fun notificationKindIcon(kind: KaPostsViewModel.NotificationItem.Kind): ImageVector = when (kind) {
    KaPostsViewModel.NotificationItem.Kind.LIKE -> Icons.Default.Favorite
    KaPostsViewModel.NotificationItem.Kind.DISLIKE -> Icons.Default.ThumbDown
    KaPostsViewModel.NotificationItem.Kind.REPLY -> Icons.Default.ChatBubble
    KaPostsViewModel.NotificationItem.Kind.QUOTE, KaPostsViewModel.NotificationItem.Kind.REPOST -> Icons.Default.Repeat
    KaPostsViewModel.NotificationItem.Kind.FOLLOW -> Icons.Default.PersonAdd
    KaPostsViewModel.NotificationItem.Kind.MENTION -> Icons.Default.AlternateEmail
    KaPostsViewModel.NotificationItem.Kind.OTHER -> Icons.Default.Notifications
}

/** iOS Item.Kind.tint. */
private fun notificationKindTint(kind: KaPostsViewModel.NotificationItem.Kind, secondary: Color): Color = when (kind) {
    KaPostsViewModel.NotificationItem.Kind.LIKE -> Color(0xFFFF3B30)
    KaPostsViewModel.NotificationItem.Kind.DISLIKE -> Color(0xFFFF9500)
    KaPostsViewModel.NotificationItem.Kind.QUOTE, KaPostsViewModel.NotificationItem.Kind.REPOST -> Color(0xFF34C759)
    KaPostsViewModel.NotificationItem.Kind.OTHER -> secondary
    else -> KaspaTeal
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
    // The completed tip, driving the sent-confirmation half sheet. Set instead of dismissing, so
    // the transaction id is handed over rather than the dialog just closing on nothing.
    var sentTransaction by remember { mutableStateOf<SentTransaction?>(null) }
    val kaspaExplorer by chatViewModel.kaspaExplorer.collectAsState()
    val paysViaPool by chatViewModel.paysToFreshPoolAddress.collectAsState()
    val estimatedFee by chatViewModel.estimatedFeeSompi.collectAsState()
    val spendingUtxos by chatViewModel.spendingUtxos.collectAsState()
    val availableKas = remember(spendingUtxos) {
        spendingUtxos.sumOf { it.utxoEntry.amount } / 100_000_000.0
    }
    val paysFromSpending by chatViewModel.spendingUtxosFromSpendingAddress.collectAsState()

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
            // A tip in flight cannot be dismissed out from under the send (iOS
            // interactiveDismissDisabled(isSending)).
            if (isSending) return@AlertDialog
            chatViewModel.setFeeRateOverride(null)
            chatViewModel.setPaymentAmount("")
            onDismiss()
        },
        properties = DialogProperties(dismissOnBackPress = !isSending, dismissOnClickOutside = !isSending),
        containerColor = colors.surface,
        title = { Text("Tip $displayName", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            // Sectioned like iOS's KaPostTipSheet Form: recipient card + destination line,
            // amount with the Kaspa logo + Available footer, fee tiers + Network Fee row.
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("TIPPING", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                Text(
                    "Your Chats Payment Privacy setting decides the destination and funding, exactly like a payment inside their chat.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
                Text("AMOUNT", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                    "Available: ${"%.8f".format(availableKas).trimEnd('0').trimEnd('.')} KAS from your " +
                        (if (paysFromSpending) "primary spending address" else "chatting address"),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                Text("FEE", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                Text(
                    "If the network is busy, Fast or Priority pays a higher fee to help your tip confirm sooner.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
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
                    // the dialog opened - so a cancelled tip never leaves an orphan chat. Added
                    // with no alias, as iOS does: the KNS name keeps resolving live rather than
                    // being frozen into a nickname.
                    chatViewModel.addContact(address, null)
                    // Re-apply the tier right before the send (sendPayment consumes the override).
                    chatViewModel.setFeeTierMultiplier(feeTier)
                    val tipSompi = Math.round((amountText.trim().toDoubleOrNull() ?: 0.0) * 100_000_000)
                    chatViewModel.sendPayment(address, amountText.trim()) { ok, error, txId ->
                        if (ok) {
                            chatViewModel.setPaymentAmount("")
                            isSending = false
                            if (txId.isNullOrEmpty()) {
                                // Queued rather than sent (no confirmed inputs yet): there is no
                                // transaction to show, so the sheet just closes (iOS).
                                chatViewModel.setFeeRateOverride(null)
                                onDismiss()
                            } else {
                                sentTransaction = SentTransaction(
                                    txId = txId,
                                    amountSompi = tipSompi,
                                    recipient = displayName,
                                )
                            }
                        } else {
                            isSending = false
                            errorText = error ?: "Tip failed."
                        }
                    }
                },
            ) {
                if (isSending) {
                    CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Text("Send Tip", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSending,
                onClick = {
                    chatViewModel.setFeeRateOverride(null)
                    chatViewModel.setPaymentAmount("")
                    onDismiss()
                },
            ) { Text("Cancel", color = colors.textSecondary) }
        },
    )

    sentTransaction?.let { sent ->
        SentConfirmationSheet(
            transaction = sent,
            explorerName = kaspaExplorer.displayName,
            explorerUrl = sent.txId.takeIf { it.isNotEmpty() }?.let { kaspaExplorer.txUrl(it) },
        ) {
            sentTransaction = null
            chatViewModel.setFeeRateOverride(null)
            onDismiss()
        }
    }
}

/** @mention machinery, hoisted: these were compiled per keystroke inside remember blocks. */
private val MENTION_QUERY_REGEX = Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]*)$", RegexOption.IGNORE_CASE)
private val MENTION_REPLACE_REGEX = Regex("@[a-z0-9-]*$", RegexOption.IGNORE_CASE)

/** Annotation tag carried by @mention ranges - ClickableText resolves it to a profile. */
const val MENTION_ANNOTATION_TAG = "mention"

/** Annotation tag carried by URL ranges - ClickableText opens a Copy/Open dialog (iOS parity:
 *  a link tap never auto-opens the browser). Value = normalized (https-prefixed) URL. */
const val LINK_ANNOTATION_TAG = "link"

/**
 * X-style translate link under the post text. Absent unless the post is confidently in another
 * language, so ordinary same-language feeds look exactly as they did.
 */
@Composable
private fun TranslateAffordance(
    state: PostTranslationService.TranslationState?,
    canTranslate: Boolean,
    showingOriginal: Boolean,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onShowTranslation: () -> Unit,
) {
    val colors = LocalAppColors.current
    when (state) {
        null -> if (canTranslate) {
            TranslateLink("Translate post", onTranslate)
        }
        PostTranslationService.TranslationState.Translating -> {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = colors.textSecondary,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Translating...",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        is PostTranslationService.TranslationState.Translated -> if (showingOriginal) {
            TranslateLink("Show translation", onShowTranslation)
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Translated from ${state.sourceName} -",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Show original",
                    color = KaspaTeal,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onShowOriginal() },
                )
            }
        }
        // A dropped connection or a server that was briefly away - tapping again once there is a
        // connection is the fix, so this stays a live link rather than dead text.
        PostTranslationService.TranslationState.Failed ->
            TranslateLink("Translation unavailable - try again", onTranslate)
        // Nothing a second tap can change - the pair is not served, the post is too long, the post
        // was already in the reader's language. Say so instead of inviting a retry.
        is PostTranslationService.TranslationState.Unavailable -> {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                state.reason,
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun TranslateLink(title: String, onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        title,
        color = KaspaTeal,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier.clickable { onClick() },
    )
}

/**
 * [range] minus every protected range, in order. Returns `[range]` when nothing overlaps, which is
 * the case for the overwhelming majority of posts.
 */
private fun subtractRanges(protectedRanges: List<IntRange>, range: IntRange): List<IntRange> {
    val overlapping = protectedRanges
        .filter { it.first <= range.last && it.last >= range.first }
        .sortedBy { it.first }
    if (overlapping.isEmpty()) return listOf(range)
    val out = mutableListOf<IntRange>()
    var cursor = range.first
    for (blocked in overlapping) {
        if (blocked.first > cursor) out += cursor until blocked.first
        cursor = maxOf(cursor, blocked.last + 1)
    }
    if (cursor <= range.last) out += cursor..range.last
    return out.filter { !it.isEmpty() }
}

/**
 * Detected links in post text, the set iOS's NSDataDetector finds: http(s) URLs, www. hosts,
 * bare domains with a common top-level domain ("kaspa.org", optionally with a path), and email
 * addresses. A bare domain must not follow an @ (that is a mention, "@alice.kas") or sit inside
 * another word, hence the lookbehind.
 */
private val POST_URL_REGEX = Regex(
    """(?i)(?:https?://|www\.)\S+""" +
        """|(?<![@\w.])[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|io|fyi|xyz|app|dev|me|co|info|ai|network|finance|exchange|to|gg|tv|us|uk|de|fr|ru|cn|jp|in|eu|ch|nl|se|no|es|it|ca|au|link|site|online|tech|club|pro|money|cash|space|world|news|blog|wiki|shop|store|social|chat|zone|one|id|lol|is|be|at|pl|cz|br|mx|ar|kr|tw|hk|sg|nz|za|ie|fi|dk|pt|gr|tr|ua|il|ae|sa|edu|gov|mil|int|biz|name|mobi|tel|asia|cat|jobs|travel|xxx)\b(?:/\S*)?""" +
        """|[\w.+-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)+""",
)

/** The tappable form of a detected link: scheme added to bare hosts, mailto: to emails. */
private fun normalizedLinkUrl(raw: String): String = when {
    raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
    raw.contains('@') && !raw.contains('/') -> "mailto:$raw"
    else -> "https://$raw"
}

/** Post text with @mention tokens tinted teal AND annotated for tap-to-profile, and URLs
 *  tinted+underlined AND annotated for the tap-to-Copy/Open dialog. */
private fun annotatedPostText(source: String): androidx.compose.ui.text.AnnotatedString {
    // Markdown first: it decides what the text actually READS as (markers gone, bullets and
    // numbers materialised), and the linkifier's offsets have to be into that string, not the
    // source. Its spans are then layered on top.
    val rendered = KaPostsMarkdown.render(source)
    val text = rendered.text
    // A mention is an identity, not prose: it always looks the same so it stays recognisable at a
    // glance, and formatting never applies to it. "**@alice.kas** ships it" bolds "ships it" and
    // leaves the mention alone.
    val mentionRanges = KaPostsViewModel.MENTION_TOKEN_REGEX.findAll(text).mapNotNull { match ->
        val domain = match.groups[2] ?: return@mapNotNull null
        val start = domain.range.first - 1 // include the '@'
        if (start < 0) null else start until (domain.range.last + 1)
    }.toList()
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        for (span in rendered.spans) {
            if (span.start >= span.end || span.end > text.length) continue
            val style = span.style
            val decorations = buildList {
                if (style.underline) add(androidx.compose.ui.text.style.TextDecoration.Underline)
                if (style.strikethrough) add(androidx.compose.ui.text.style.TextDecoration.LineThrough)
                // An explicit [label](url) link is underlined like a detected one, so the two
                // kinds of link look the same to a reader.
                if (style.link != null) add(androidx.compose.ui.text.style.TextDecoration.Underline)
            }
            val spanStyle = androidx.compose.ui.text.SpanStyle(
                fontWeight = if (style.bold) FontWeight.Bold else null,
                fontStyle = if (style.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                fontSize = if (style.subtext) 13.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = when {
                    style.link != null -> KaspaTeal
                    style.subtext -> Color(0xFF8A8A8E)
                    else -> Color.Unspecified
                },
                textDecoration = if (decorations.isEmpty()) {
                    null
                } else {
                    androidx.compose.ui.text.style.TextDecoration.combine(decorations)
                },
            )
            // A span crossing a mention is applied to the pieces either side of it, never over it.
            for (piece in subtractRanges(mentionRanges, span.start until span.end)) {
                addStyle(spanStyle, piece.first, piece.last + 1)
                if (style.link != null) {
                    addStringAnnotation(LINK_ANNOTATION_TAG, style.link, piece.first, piece.last + 1)
                }
            }
        }
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
            addStringAnnotation(LINK_ANNOTATION_TAG, normalizedLinkUrl(raw), start, end)
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
    KaPostsViewModel.NotificationItem.Kind.MENTION -> "mentioned you in a post"
    KaPostsViewModel.NotificationItem.Kind.OTHER -> "interacted with your post"
}

// MARK: - Follow list overlay

@OptIn(ExperimentalMaterial3Api::class)
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
    // Pull-to-refresh reloads page one (iOS .refreshable).
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.loadFollowList(followers, targetPubkey)
            delay(400)
            pullRefreshState.endRefresh()
        }
    }

    KaPostsOverlayScaffold(title = if (followers) "Followers" else "Following", onClose = onClose) {
        val list = entries
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection),
        ) {
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
                Icon(
                    if (followers) Icons.Default.Group else Icons.Default.PersonAdd,
                    null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(44.dp),
                )
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
                // targetPubkey passed through: a retry on someone else's list must page THEIR
                // list, not the signed-in user's.
                pagingFooter(paging, keySuffix = "follows") { viewModel.loadMoreFollowList(followers, targetPubkey, manual = true) }
            }
        }
        if (pullRefreshState.verticalOffset > 0f || pullRefreshState.isRefreshing) {
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
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
                            viewModel.loadMoreEngagement(post, selectedTab, manual = true)
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.surfaceVariant)
            post.remoteId?.let { txId ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(kaspaExplorer.txUrl(txId)) }
                        .padding(vertical = 14.dp),
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "View Post Transaction in Explorer",
                        color = KaspaTeal,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
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
                Icon(
                    if (blocked) Icons.Default.PanTool else Icons.Default.VolumeOff,
                    null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    if (blocked) "No blocked accounts" else "No muted accounts",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    if (blocked) "Blocked accounts are removed everywhere and can't interact with you."
                    else "Accounts you mute disappear from your feeds but can still interact with you.",
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
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
                            size = 40.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            rowName,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val hapticView = LocalView.current
                        OutlinedButton(
                            onClick = {
                                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                if (blocked) viewModel.unblock(address) else viewModel.unmute(address)
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, KaspaTeal.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KaspaTeal),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(if (blocked) "Unblock" else "Unmute", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
    /** The reply bubble opens the Reply composer; the card itself is inert here (iOS). */
    onReply: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onTip: ((KaPostDraft) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    // Recompute against the live lists so un-bookmarking updates immediately - but the
    // full-tree scan only re-runs when one of those lists actually changed.
    val tree by viewModel.postTree.collectAsState()
    val bookmarks = remember(tree) { viewModel.bookmarkedPosts() }

    KaPostsOverlayScaffold(title = "Bookmarks", onClose = onClose) {
        if (bookmarks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.BookmarkBorder, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(14.dp))
                Text("No bookmarks yet", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Tap the bookmark on any post to save it here.",
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn {
                items(bookmarks, key = { "bookmark-${it.id}" }) { post ->
                    LaunchedEffect(post.posterAddress) { viewModel.ensureSenderProfileFetched(post.posterAddress) }
                    KaPostCell(
                        post = post,
                        viewModel = viewModel,
                        // Bookmarks has no thread surface of its own, so the card tap stays inert.
                        onOpenThread = {},
                        onRepostTap = { onRepostTap(post) },
                        onViewEngagement = { onViewEngagement(post) },
                        onReply = { onReply(post) },
                        onTip = onTip?.let { tip -> { tip(post) } },
                    )
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 68.dp))
                }
            }
        }
    }
}

/** Shared full-screen overlay chrome: back arrow + bold title over the app background. */
/**
 * The feed's own header indicators - clickable connection dot leading, chatting balance centred -
 * repeated on every overlay that covers the feed. Reading a thread or a profile is still "being in
 * KaPosts", and both answer questions that come up mid-read: whether a like that is not landing is
 * the network's fault, and whether there is enough KAS to reply at all. The overlays are separate
 * Dialog windows, so nothing behind them shows through.
 */
@Composable
internal fun KaPostsOverlayStatusBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        ConnectionDotButton(
            onClick = { ConnectionStatusOverlayState.open() },
            modifier = Modifier.align(Alignment.CenterStart),
        )
        BalanceTopBarLabel(modifier = Modifier.align(Alignment.Center))
    }
}

/**
 * The overlays' navigation bar as iOS draws it: an inline centred title and ONE trailing text
 * control that closes the screen - "Back" everywhere, "Done" on Search.
 */
@Composable
internal fun KaPostsOverlayHeader(title: String, closeLabel: String, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(44.dp),
    ) {
        Text(
            title,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            modifier = Modifier.align(Alignment.Center),
        )
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
            Text(closeLabel, color = KaspaTeal, fontSize = 16.sp)
        }
    }
    HorizontalDivider(color = colors.surfaceVariant)
}

@Composable
private fun KaPostsOverlayScaffold(
    title: String,
    onClose: () -> Unit,
    closeLabel: String = "Back",
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
            KaPostsOverlayStatusBar()
            KaPostsOverlayHeader(title = title, closeLabel = closeLabel, onClose = onClose)
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
                // "Posting in 4s" ticking down, then an orange underlined Undo (iOS toastOverlay).
                var remainingMs by remember(toast.deadlineMs) { mutableLongStateOf(toast.deadlineMs - System.currentTimeMillis()) }
                LaunchedEffect(toast.deadlineMs) {
                    while (remainingMs > 0) {
                        delay(250)
                        remainingMs = toast.deadlineMs - System.currentTimeMillis()
                    }
                }
                val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.surfaceVariant, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("${toast.label} in ${seconds}s", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Undo",
                        color = Color(0xFFFF9500),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
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
                        Icons.Default.CheckCircle, null,
                        tint = Color(0xFF34C759),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(toast.message, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "View",
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.clickable { onViewTx(toast.txId) },
                    )
                }
            }
        }
    }
}

// MARK: - Helpers

/**
 * Relative timestamp in the abbreviated form iOS's RelativeDateTimeFormatter produces: "5 sec.
 * ago", "3 min. ago", "2 hr. ago", "3 days ago", "1 wk. ago", "2 mo. ago", "1 yr. ago".
 */
fun relativePostTime(timestampMs: Long): String {
    val deltaSec = ((System.currentTimeMillis() - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        deltaSec < 60 -> "$deltaSec sec. ago"
        deltaSec < 3600 -> "${deltaSec / 60} min. ago"
        deltaSec < 86_400 -> "${deltaSec / 3600} hr. ago"
        deltaSec < 7 * 86_400 -> (deltaSec / 86_400).let { if (it == 1L) "1 day ago" else "$it days ago" }
        deltaSec < 30 * 86_400 -> "${deltaSec / (7 * 86_400)} wk. ago"
        deltaSec < 365 * 86_400 -> "${deltaSec / (30 * 86_400)} mo. ago"
        else -> "${deltaSec / (365 * 86_400)} yr. ago"
    }
}

/** 1234 -> "1.2K" etc., X-style compact counters. */
fun formatEngagementCount(count: Int): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000f).removeSuffix(".0M").let { if (it.endsWith("M")) it else it + "M" }
    count >= 1_000 -> "%.1fK".format(count / 1_000f).removeSuffix(".0K").let { if (it.endsWith("K")) it else it + "K" }
    else -> count.toString()
}

/**
 * The floating "Show N new posts" pill, X-style.
 *
 * Floating rather than a row in the list: a row only exists where it was inserted, so a reader who
 * has scrolled past it never learns there is anything new. This stays put at the top of the feed.
 * Matches iOS.
 */
@Composable
private fun NewPostsPill(count: Int, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = count > 0,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = Modifier.fillMaxWidth().zIndex(1f)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(KaspaTeal)
                    .clickable { onClick() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (count == 1) "Show 1 new post" else "Show $count new posts",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}


/** One KaPosts destination in the header's icon row - see the row's own comment. */
@Composable
private fun KaPostsMenuIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    /** Unseen count for this destination, drawn as a badge. Zero draws nothing. Declared before
     *  [onClick] so a trailing lambda still binds to the click, as every other call site here
     *  writes it. */
    badgeCount: Int = 0,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
        Box {
            Icon(
                icon,
                contentDescription = if (badgeCount > 0) {
                    "$contentDescription, $badgeCount unseen"
                } else {
                    contentDescription
                },
                tint = LocalAppColors.current.textPrimary,
                modifier = Modifier.size(26.dp),
            )
            // A count rather than a plain dot: in here you are one tap from the list, so how many
            // are waiting is worth knowing before you decide to look.
            if (badgeCount > 0) {
                Text(
                    if (badgeCount > 99) "99+" else badgeCount.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFE0245E))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}


/**
 * A profile bio that always shows up to three lines, with a More button when there is more.
 *
 * Truncation is MEASURED rather than guessed from length: the same string wraps to a different
 * number of lines depending on width and text size, so a character threshold would both hide More
 * on a long-but-narrow bio and show it on a short one that already fits. Compose reports this
 * directly through `hasVisualOverflow`. Mirrors iOS's `ExpandableBioText`, which has to measure
 * two hidden copies to learn the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandableBioText(bio: String) {
    val colors = LocalAppColors.current
    var isTruncated by remember(bio) { mutableStateOf(false) }
    var showFullBio by remember { mutableStateOf(false) }

    Column {
        Text(
            bio,
            color = colors.textSecondary,
            fontSize = 14.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { isTruncated = it.hasVisualOverflow },
        )
        if (isTruncated) {
            Text(
                "More",
                color = KaspaTeal,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { showFullBio = true },
            )
        }
    }

    if (showFullBio) {
        ModalBottomSheet(
            onDismissRequest = { showFullBio = false },
            containerColor = colors.background,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Bio", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    bio,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
