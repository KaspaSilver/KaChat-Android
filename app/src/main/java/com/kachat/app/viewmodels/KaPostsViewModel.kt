package com.kachat.app.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.sync.withPermit
import com.kachat.app.models.KaPostDraft
import com.kachat.app.models.findPostByRemoteIdIn
import com.kachat.app.models.findPostIn
import com.kachat.app.models.mutatePostIn
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.KPost
import com.kachat.app.services.KaPostsService
import com.kachat.app.services.KnsService
import com.kachat.app.services.PostTranslationService
import com.kachat.app.services.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * KaPosts state machine - the Android port of iOS KaPostsView's logic. Feeds come from the K
 * indexer (KaChat-marker-filtered); every action (post, reply, vote, quote, follow) is an
 * on-chain self-send transaction. Post/quote/like/dislike submits are held behind a 5-second
 * undo countdown; undo cancels before anything touches the network.
 */
@HiltViewModel
class KaPostsViewModel @Inject constructor(
    private val kaPostsService: KaPostsService,
    private val walletManager: WalletManager,
    private val knsService: KnsService,
    private val chainReader: com.kachat.app.services.KaPostChainReader,
    private val chatRepository: ChatRepository,
    private val settings: AppSettingsRepository,
    private val translationService: PostTranslationService,
    private val unseenStore: com.kachat.app.services.KaPostsUnseenStore,
    /** What KNS said about an address last time the app ran - see [KnsProfileCacheStore]. */
    private val knsProfileCache: com.kachat.app.services.KnsProfileCacheStore,
    /** The foreground ping poller: the Notifications screen tells it what has been seen. */
    private val notificationPoller: com.kachat.app.services.KaPostsNotificationPoller,
    /** Which posts were already probed for being thread roots, across launches. */
    private val threadProbeStore: com.kachat.app.services.KaPostsThreadProbeStore,
) : ViewModel() {

    /** How many KaPosts notifications have arrived since the bell was last opened. */
    val unseenNotifications: kotlinx.coroutines.flow.StateFlow<Int> = unseenStore.unseenCount

    /** The user has opened the notifications list; nothing is unseen any more. */
    fun markNotificationsSeen() {
        unseenStore.markAllSeen()
    }

    companion object {
        private const val TAG = "KaPostsViewModel"
        const val UNDO_DELAY_MS = 5_000L

        /** @mention token: @domain at start / after whitespace or opening punctuation (never
         *  inside an email). Same pattern as desktop/iOS and the indexer contract. */
        val MENTION_TOKEN_REGEX = Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]+(?:\\.[a-z0-9-]+)*)", RegexOption.IGNORE_CASE)

        /** Rows requested per HTTP page. Matches iOS's KaPostsAPIClient.pageSize: big enough that
         *  the KaChat-marker filter usually still leaves something behind. */
        private const val PAGE_LIMIT = 50

        /** A thread's replies page. iOS reads a hundred at a time: a conversation is read top to
         *  bottom, so fewer round trips beat smaller pages here. */
        private const val THREAD_REPLIES_PAGE_LIMIT = 100

        /** Follow lists and the follow-set chain sync read a hundred accounts per page (iOS). */
        private const val FOLLOW_PAGE_LIMIT = 100

        /** How many NEW VISIBLE rows one load-more trigger tries to accumulate. */
        private const val TARGET_NEW_ROWS = 18

        /** Hard cap on HTTP requests per trigger, so a feed that is 99% non-KaChat content can
         *  never turn one flick of the thumb into an unbounded crawl of the whole index. */
        private const val MAX_REQUESTS_PER_TRIGGER = 5

        /**
         * How many ranked posts the Popular tab wants under it before its top row means anything.
         *
         * The indexer has no popularity endpoint - every feed comes back reverse-chronological -
         * so Popular can only rank what the client has actually pulled. Ranking one page made
         * "most popular" mean "the most-liked of the last couple of dozen posts", and a genuinely
         * big post from last week never appeared. Popular sweeps this far back before it trusts
         * its own order.
         */
        private const val POPULAR_RANKING_DEPTH = 300

        /** Request budget for one sweep pass (see [KaPostsViewModel.deepenPopularRanking]). */
        private const val POPULAR_SWEEP_REQUESTS_PER_PASS = 6

        /**
         * Hard ceiling on sweep passes. Heavily-filtered stretches of history (a run of non-KaChat
         * posts, a muted author) can return two visible rows for a whole pass, so depth alone is
         * not a bound - without this, one tab tap could spend dozens of requests on cellular.
         */
        private const val POPULAR_SWEEP_MAX_PASSES = 4

        /** How close to the end of a list counts as "nearing the end" (in rows). */
        const val LOAD_MORE_THRESHOLD = 5

        // Paging keys. Everything that scrolls endlessly owns one, and surfaces that exist per
        // post/per account derive theirs so a different post or account is a different surface.
        const val PAGE_GLOBAL_FEED = "feed:global"
        const val PAGE_FOLLOWING_FEED = "feed:following"
        const val PAGE_NOTIFICATIONS = "notifications"
        fun pageProfile(pubkey: String, isMine: Boolean, replies: Boolean): String =
            "profile:${if (isMine) "me" else "them"}:$pubkey:${if (replies) "replies" else "posts"}"
        fun pageThread(remoteId: String): String = "thread:$remoteId"
        fun pageEngagement(postId: String): String = "engagement:$postId"
        fun pageFollowList(followers: Boolean): String =
            "follows:${if (followers) "followers" else "following"}"
    }

    enum class FeedTab { FOLLOWING, FEED, POPULAR }

    // MARK: - Endless-scroll paging engine

    /**
     * Paging state for ONE endless-scrolling surface.
     *
     * [cursor] is the server's opaque `before` value for the next page - never offset math.
     * [hasMore] is sticky-false: once a surface reaches the end it stops asking until it is reset.
     */
    data class PagingState(
        val cursor: String? = null,
        val hasMore: Boolean = true,
        val isLoadingMore: Boolean = false,
        /** Set when a load-more failed. The list is KEPT and the UI offers a retry row. */
        val error: String? = null,
        /**
         * A whole request budget produced zero visible rows while the server still has pages.
         * Auto-loading stops here and the footer turns into an explicit "Load more" button, so a
         * stretch of history that is all filtered away cannot turn one scroll into an unattended
         * crawl of the index. A manual tap clears it. Mirrors iOS's KaPostsPageState.stalled.
         */
        val stalled: Boolean = false,
    )

    private val _paging = MutableStateFlow<Map<String, PagingState>>(emptyMap())
    val paging: StateFlow<Map<String, PagingState>> = _paging.asStateFlow()

    fun pagingState(key: String): PagingState = _paging.value[key] ?: PagingState()

    private fun updatePaging(key: String, transform: (PagingState) -> PagingState) {
        _paging.value = _paging.value + (key to transform(_paging.value[key] ?: PagingState()))
    }

    /**
     * Generation per surface, bumped on every reset (refresh, account switch, a different post's
     * thread). An in-flight load that finishes after its generation moved on is DROPPED rather
     * than appended, so stale pages can never land in a list they no longer belong to.
     */
    private val generations = mutableMapOf<String, Int>()
    private val loadMoreJobs = mutableMapOf<String, Job>()

    /** Replies written in this session, by local id. They sit at the top of their thread until
     *  the indexer returns them; a thread reload that does not yet include one keeps it there
     *  rather than dropping it for the seconds indexing takes. */
    private val sessionReplyIds = mutableSetOf<String>()

    /** What an edit replaced, kept for the five seconds Undo can put it back. */
    private val pendingEditOriginals = mutableMapOf<String, Pair<String, Long?>>()

    /** The Popular tab's deep ranking sweep (see [deepenPopularRanking]); at most one at a time. */
    private var popularSweepJob: Job? = null

    /** True once this surface has been loaded at least once in this session. */
    private fun surfaceLoaded(key: String): Boolean = generations.containsKey(key)

    private fun resetSurface(key: String): Int {
        val generation = (generations[key] ?: 0) + 1
        generations[key] = generation
        loadMoreJobs.remove(key)?.cancel()
        // A refresh or account change invalidates the window the sweep was extending; letting it
        // continue would append pages from the old cursor onto a list that just started over.
        if (key == PAGE_GLOBAL_FEED) popularSweepJob?.cancel()
        _paging.value = _paging.value + (key to PagingState())
        return generation
    }

    /** What one fetch loop produced: new rows (hidden ones included), where to resume, and why it stopped. */
    private data class Accumulation<R>(
        val items: List<R>,
        val cursor: String?,
        val hasMore: Boolean,
        val error: String? = null,
        /** The budget ran out with nothing visible to show for it - see [PagingState.stalled]. */
        val stalled: Boolean = false,
    )

    /**
     * THE filter-shrinkage loop.
     *
     * Server pages are filtered twice on the way in - to KaChat-marked content in the service, and
     * to non-muted/non-blocked authors here - so a page of 25 can yield two visible rows. Loading
     * exactly one page per trigger therefore stalls a feed that still has plenty to show. This
     * keeps requesting further pages until [target] NEW VISIBLE rows have accumulated, the server
     * reports no more pages, or [MAX_REQUESTS_PER_TRIGGER] requests have been spent.
     *
     * [map] returns null for rows that must not enter the list at all (unmappable, wrong kind);
     * [isVisible] marks rows the UI would currently render - only those count toward [target],
     * while everything mapped is still appended, so unmuting an author brings their posts back
     * without a refetch. [seenIds] are the ids already held, which is where dedup happens.
     */
    private suspend fun <T, R> accumulate(
        startCursor: String?,
        target: Int,
        seenIds: Set<String>,
        maxRequests: Int = MAX_REQUESTS_PER_TRIGGER,
        idOf: (T) -> String,
        map: (T) -> R?,
        isVisible: (R) -> Boolean,
        fetch: suspend (before: String?) -> com.kachat.app.services.KPage<T>,
    ): Accumulation<R> {
        val collected = mutableListOf<R>()
        val seen = seenIds.toMutableSet()
        var cursor = startCursor
        var hasMore = true
        var visibleCount = 0
        var requests = 0
        var error: String? = null
        while (requests < maxRequests && visibleCount < target && hasMore) {
            requests++
            val page = try {
                fetch(cursor)
            } catch (e: Exception) {
                Log.w(TAG, "Page fetch failed", e)
                error = e.message ?: "Could not load more"
                break
            }
            val fresh = page.rawIds.filterNot { it in seen }.toSet()
            for (item in page.items) {
                if (idOf(item) !in fresh) continue
                val mapped = map(item) ?: continue
                collected += mapped
                if (isVisible(mapped)) visibleCount++
            }
            seen += page.rawIds
            val next = page.cursor
            // End of the line when the server says so, when the page was empty, when a
            // deployment ignored `before` and replayed rows we already hold, or when the cursor
            // refuses to advance - anything else would spin.
            if (!page.hasMore || page.rawIds.isEmpty() || fresh.isEmpty() || next == null || next == cursor) {
                hasMore = false
            } else {
                cursor = next
            }
        }
        val stalled = error == null && hasMore && requests >= maxRequests && visibleCount == 0
        return Accumulation(collected, cursor, hasMore, error, stalled)
    }

    // MARK: - Feed state

    private val _selectedFeed = MutableStateFlow(FeedTab.FEED)
    val selectedFeed: StateFlow<FeedTab> = _selectedFeed.asStateFlow()

    /** Local session posts (composer output) - overlaid on top of remote posts until indexed. */
    private val _localPosts = MutableStateFlow<List<KaPostDraft>>(emptyList())
    val localPosts: StateFlow<List<KaPostDraft>> = _localPosts.asStateFlow()

    /**
     * Posts fetched from the K indexer (already KaChat-marker-filtered by the service), split by
     * the endpoint that produced them: the global "watching" stream feeds both Feed and Popular
     * (Popular is that same set re-sorted), while Following comes from get-contents-following and
     * so must accumulate its own pages behind its own cursor.
     */
    private val _globalPosts = MutableStateFlow<List<KaPostDraft>>(emptyList())
    private val _followingPosts = MutableStateFlow<List<KaPostDraft>>(emptyList())

    private val _isLoadingFeed = MutableStateFlow(false)
    val isLoadingFeed: StateFlow<Boolean> = _isLoadingFeed.asStateFlow()

    private val _feedError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _feedError.asStateFlow()

    // MARK: - Local stores (follow/mute/block survive relaunch; on-chain follow txs mirror them)

    /**
     * The ACTIVE account's follow set, re-keyed on every account switch. The persisted key is
     * scoped by wallet address (see AppSettingsRepository) and flatMapLatest swaps to the new
     * account's key the moment activeAddressFlow moves, so no in-memory follow state can
     * survive a switch - the old global key made every account on the device (fresh ones
     * included) share one follow set.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val following: StateFlow<Set<String>> = walletManager.activeAddressFlow
        .flatMapLatest { address ->
            if (address == null) flowOf(emptySet<String>()) else settings.kapostsFollowing(address)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    // Mutes and blocks are per account too, swapped with the active address exactly like the
    // follow set; the pre-scoping lists are adopted by a device's only account (see
    // AppSettingsRepository.migrateLegacyKapostsModeration).
    @OptIn(ExperimentalCoroutinesApi::class)
    val muted: StateFlow<Set<String>> = walletManager.activeAddressFlow
        .flatMapLatest { address ->
            if (address == null) flowOf(emptySet<String>()) else {
                settings.migrateLegacyKapostsModeration(address, singleAccount = walletManager.getAllAccounts().size <= 1)
                settings.kapostsMuted(address)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    @OptIn(ExperimentalCoroutinesApi::class)
    val blocked: StateFlow<Set<String>> = walletManager.activeAddressFlow
        .flatMapLatest { address ->
            if (address == null) flowOf(emptySet<String>()) else {
                settings.migrateLegacyKapostsModeration(address, singleAccount = walletManager.getAllAccounts().size <= 1)
                settings.kapostsBlocked(address)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun isHidden(address: String, mutedSet: Set<String> = muted.value, blockedSet: Set<String> = blocked.value): Boolean =
        address in mutedSet || address in blockedSet

    /** Session posts first, then remote posts deduped by remote id, muted/blocked authors dropped. */
    private fun overlayLocal(
        local: List<KaPostDraft>,
        remote: List<KaPostDraft>,
        hiddenSet: Set<String>,
    ): List<KaPostDraft> {
        val combined = local + remote.filter { r ->
            local.none { it.remoteId != null && it.remoteId == r.remoteId }
        }
        return combined.filter { it.posterAddress !in hiddenSet }
    }

    /** The global stream as rendered: backs the Feed and Popular tabs. */
    val visiblePosts: StateFlow<List<KaPostDraft>> = combine(
        _localPosts, _globalPosts, combine(muted, blocked) { m, b -> m + b },
    ) { local, remote, hiddenSet -> overlayLocal(local, remote, hiddenSet) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The Following stream as rendered. get-contents-following is already scoped to the on-chain
     * follow graph; the local follow set is applied on top (it is what the Follow buttons write,
     * and the indexer lags behind it), which is exactly the shrinkage the fetch loop measures.
     */
    val visibleFollowingPosts: StateFlow<List<KaPostDraft>> = combine(
        _localPosts, _followingPosts, combine(muted, blocked) { m, b -> m + b }, following,
    ) { local, remote, hiddenSet, followingSet ->
        overlayLocal(local, remote, hiddenSet).filter { it.posterAddress in followingSet }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * One tab's rows. Pure, so the pager can render the neighbouring pages without them having to
     * be the selected tab. Popular re-sorts the global set rather than reading its own endpoint.
     */
    fun feedFor(
        tab: FeedTab,
        globalVisible: List<KaPostDraft>,
        followingVisible: List<KaPostDraft>,
    ): List<KaPostDraft> = when (tab) {
        FeedTab.FOLLOWING -> followingVisible
        FeedTab.FEED -> globalVisible
        // Every interaction counts - a post people argue with is popular in the same sense a post
        // people like is - including comments, which the cell already shows but the old ordering
        // ignored entirely. Ties break by recency so the many equal-scoring posts deep in the
        // window keep a stable order instead of the sort's whim.
        //
        // Scored once per post rather than once per comparison: the ranking window is 300 posts
        // deep, so a comparator that recomputes the score would call it a couple of thousand
        // times, and a tab tap composes this page mid-animation.
        FeedTab.POPULAR -> globalVisible
            .map { it to popularityScore(it) }
            .sortedWith(
                compareByDescending<Pair<KaPostDraft, Int>> { it.second }
                    .thenByDescending { it.first.timestamp }
            )
            .map { it.first }
    }

    /** The selected tab's feed - kept for callers that only care about what's on screen. */
    val visibleFeed: StateFlow<List<KaPostDraft>> = combine(
        visiblePosts, visibleFollowingPosts, _selectedFeed,
    ) { global, followingFeed, tab -> feedFor(tab, global, followingFeed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // MARK: - New posts waiting

    /**
     * Posts that arrived since the feed was last loaded, held back rather than inserted.
     *
     * Splicing new rows in under a reader moves everything they were looking at. Holding them and
     * offering them is better: nothing shifts until the reader asks, and the count tells them
     * whether it is worth asking.
     */
    private val _pendingNewPosts = MutableStateFlow<List<KaPostDraft>>(emptyList())
    val pendingNewPosts: StateFlow<List<KaPostDraft>> = _pendingNewPosts.asStateFlow()

    private var checkingForNewPosts = false

    /**
     * How often the visible feed asks whether anything newer exists. One page, and only while the
     * feed is on screen - the same cadence as the app's other fallback polls, chosen so a social
     * feed still feels current without becoming a background data drain on cellular.
     */
    val newPostsCheckIntervalMs = 60_000L

    /**
     * Asks whether anything newer than the loaded feed exists, without touching what is on screen.
     *
     * Deliberately page ONE only: this answers "is there anything new", not "fetch everything I
     * missed" - pulling the gap in full would be a lot of requests for a yes/no question, and
     * showing the posts refreshes properly anyway.
     */
    fun checkForNewPosts(tab: FeedTab = _selectedFeed.value) {
        if (checkingForNewPosts) return
        // The feed's poll loop survives backgrounding (the screen is still composed), so this is
        // the foreground half of the promise: no network from a feed nobody is looking at. The
        // next tick after resume picks it up (iOS gates on applicationState).
        val lifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        val key = feedKey(tab)
        if (pagingState(key).isLoadingMore || _isLoadingFeed.value) return
        val known = feedFlow(tab).value.mapNotNull { it.remoteId }.toSet()
        if (known.isEmpty()) return

        checkingForNewPosts = true
        viewModelScope.launch {
            try {
                val page = fetchFeedPage(tab, null)
                if (tab != _selectedFeed.value) return@launch
                val hidden = muted.value + blocked.value
                val fresh = page.items
                    .filterNot { it.id in known }
                    .mapNotNull { mapRemotePost(it) }
                    .filterNot { it.posterAddress in hidden }
                if (fresh.isNotEmpty()) _pendingNewPosts.value = fresh
            } catch (e: Exception) {
                // Silent: it is a background question, and the feed on screen is still usable.
                Log.w(TAG, "New-post check failed", e)
            } finally {
                checkingForNewPosts = false
            }
        }
    }

    /** Splices the held posts in at the top, on request. */
    fun showPendingNewPosts(tab: FeedTab = _selectedFeed.value) {
        val pending = _pendingNewPosts.value
        if (pending.isEmpty()) return
        val flow = feedFlow(tab)
        val known = flow.value.mapNotNull { it.remoteId }.toSet()
        val additions = pending.filter { it.remoteId == null || it.remoteId !in known }
        flow.value = additions + flow.value
        _pendingNewPosts.value = emptyList()
    }

    private fun clearPendingNewPosts() {
        _pendingNewPosts.value = emptyList()
    }

    // MARK: - On-device post translation

    /**
     * Per-post translation state, keyed by the post's remote id (its txid) so a translation
     * survives the feed being re-sorted or re-paged; local session posts key by their local id.
     */
    private val _translations = MutableStateFlow<Map<String, PostTranslationService.TranslationState>>(emptyMap())
    val translations: StateFlow<Map<String, PostTranslationService.TranslationState>> = _translations.asStateFlow()

    /** Posts the reader flipped back to the original. Separate from [_translations] so toggling
     *  back and forth never re-runs the translation. */
    private val _showingOriginal = MutableStateFlow<Set<String>>(emptySet())
    val showingOriginal: StateFlow<Set<String>> = _showingOriginal.asStateFlow()

    /**
     * Posts we have decided ARE worth offering a Translate link for. Language identification is
     * async, so the cell cannot ask the question inline while rendering; it calls
     * [considerTranslation] once per post and this set drives the affordance when the answer
     * arrives. Posts already in the reader's language simply never appear here.
     */
    private val _translatable = MutableStateFlow<Map<String, String>>(emptyMap())
    val translatable: StateFlow<Map<String, String>> = _translatable.asStateFlow()

    private val considered = mutableSetOf<String>()

    /** The reader language every entry in [considered] and [_translatable] was decided under.
     *  Settings > Language recreates the Activity but not this ViewModel, so without this a reader
     *  who switches language keeps the previous language's offers for the rest of the session. */
    private var consideredLanguage: String? = null

    fun translationKey(post: KaPostDraft): String = post.remoteId ?: post.id

    /** Identifies the post's language once, and records it if it is worth offering to translate. */
    fun considerTranslation(post: KaPostDraft) {
        val language = translationService.targetLanguage()
        if (language != consideredLanguage) {
            consideredLanguage = language
            considered.clear()
            _translatable.value = emptyMap()
        }
        val key = translationKey(post)
        if (!considered.add(key)) return
        viewModelScope.launch {
            val source = translationService.detectLanguage(post.text) ?: return@launch
            // Pass the source we already have: canOfferTranslation would otherwise identify the
            // same text a second time.
            if (!translationService.canOfferTranslation(post.text, source)) return@launch
            _translatable.value = _translatable.value + (key to source)
        }
    }

    fun translatePost(post: KaPostDraft) {
        val key = translationKey(post)
        val source = _translatable.value[key] ?: return
        _showingOriginal.value = _showingOriginal.value - key
        // A second tap while one is in flight must not start a second request.
        if (_translations.value[key] == PostTranslationService.TranslationState.Translating) return
        _translations.value = _translations.value + (key to PostTranslationService.TranslationState.Translating)
        viewModelScope.launch {
            val next = try {
                val result = translationService.translate(post.text, post.remoteId)
                PostTranslationService.TranslationState.Translated(
                    text = result.text,
                    // The server's detection is what the line reports; our local guess only
                    // decides whether to offer the link at all. No source from the server reads
                    // as "another language" rather than a guess stated as fact (iOS).
                    sourceName = result.sourceLanguage?.let { translationService.displayName(it) }
                        ?: "another language",
                )
            } catch (e: PostTranslationService.TranslationException) {
                Log.w(TAG, "Translation failed", e)
                // A terminal answer is stated, not offered as a retry: tapping again gets it back.
                if (e.terminal) PostTranslationService.TranslationState.Unavailable(e.readerMessage)
                else PostTranslationService.TranslationState.Failed
            } catch (e: Exception) {
                Log.w(TAG, "Translation failed", e)
                PostTranslationService.TranslationState.Failed
            }
            _translations.value = _translations.value + (key to next)
        }
    }

    fun showOriginal(post: KaPostDraft) {
        _showingOriginal.value = _showingOriginal.value + translationKey(post)
    }

    fun showTranslation(post: KaPostDraft) {
        _showingOriginal.value = _showingOriginal.value - translationKey(post)
    }

    /** The text a cell should render: the translation unless there is none yet, it failed, or the
     *  reader asked for the original back. */
    fun displayText(
        post: KaPostDraft,
        state: PostTranslationService.TranslationState?,
        showingOriginal: Boolean,
    ): String = if (state is PostTranslationService.TranslationState.Translated && !showingOriginal) {
        state.text
    } else {
        post.text
    }

    private fun resetTranslations() {
        _translations.value = emptyMap()
        _showingOriginal.value = emptySet()
        _translatable.value = emptyMap()
        considered.clear()
    }

    /** KaPosts came on screen: re-read what the translation service can serve (iOS onAppear). */
    fun refreshTranslationLanguages() {
        viewModelScope.launch { translationService.refreshSupportedLanguages() }
    }

    // MARK: - Composer fee estimate (Settings > Show Fee Estimate)

    val showFeeEstimate: StateFlow<Boolean> = settings.showFeeEstimate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * What a post of [text] would cost, in sompi, for the composer's "Est. fee" line. Sized off
     * the real payload shape (dummy pubkey and signature of the real fixed lengths, marker
     * included, no mentions) with the typical single input and the single self output a
     * zero-amount self-send actually has - mirrors iOS's KaPostsAPIClient.estimatePostFee.
     */
    fun estimatePostFeeSompi(text: String): Long {
        val b64 = com.kachat.app.util.KaPostsProtocol.b64(com.kachat.app.util.KaPostsProtocol.KACHAT_MARKER + text)
        val payload = com.kachat.app.util.KaPostsProtocol.postPayload(
            pubkey = "0".repeat(66), signature = "0".repeat(128), b64Message = b64, mentionsJson = "[]",
        )
        val mass = com.kachat.app.util.KaspaMass.calculateMass(
            numInputs = 1,
            outputScriptLens = listOf(34),
            payloadSize = payload.toByteArray(Charsets.UTF_8).size,
        )
        return com.kachat.app.util.KaspaMass.calculateFee(mass, null)
    }

    // MARK: - Toasts + undo scheduler

    /** Transient confirmation that an on-chain action landed, with a link to the tx. */
    data class ActionToast(val id: String = UUID.randomUUID().toString(), val message: String, val txId: String)

    private val _actionToast = MutableStateFlow<ActionToast?>(null)
    val actionToast: StateFlow<ActionToast?> = _actionToast.asStateFlow()

    /** 5-second undo window for a just-composed post/quote. */
    data class UndoToast(
        val key: String,
        val postId: String,
        val deadlineMs: Long,
        val label: String,
        /**
         * What was typed, so Undo can hand it back rather than throw it away.
         *
         * The five seconds exist for the moment you spot a typo as the toast appears. Undoing
         * and losing the text means retyping it, which is a worse outcome than the mistake.
         * Null for the reactions (like / dislike / repost) - nothing was composed.
         */
        val draftText: String? = null,
        /** The post a quote was aimed at, so Undo reopens the quote composer still on it. */
        val quoteTargetId: String? = null,
        /**
         * Every segment of a thread, in order, so Undo rebuilds the whole chain and not just
         * its first post. The composer submits `threadSegments + [current text]`, so restoring
         * splits it back that way.
         */
        val draftSegments: List<String>? = null,
        /** The post an undone comment answered, so Undo reopens the reply composer on it. */
        val commentParentId: String? = null,
    )

    /** A draft handed back by Undo, for whichever composer is about to reopen. */
    data class RestoredDraft(
        val text: String,
        val quoteTargetId: String?,
        val isComment: Boolean,
        /** Segments to stack ABOVE [text] in the composer; empty for a single post. */
        val threadSegments: List<String> = emptyList(),
        /** For a comment: the LOCAL id of the post it was answering. */
        val commentParentId: String? = null,
    )

    private val _restoredDraft = MutableStateFlow<RestoredDraft?>(null)
    val restoredDraft: StateFlow<RestoredDraft?> = _restoredDraft.asStateFlow()

    /** Consumed by the UI once it has reopened the composer with it. */
    fun clearRestoredDraft() { _restoredDraft.value = null }

    private val _undoToast = MutableStateFlow<UndoToast?>(null)
    val undoToast: StateFlow<UndoToast?> = _undoToast.asStateFlow()

    /** Fire deadline (epoch ms) per pending action key - cells read this to render countdowns. */
    private val _undoDeadlines = MutableStateFlow<Map<String, Long>>(emptyMap())
    val undoDeadlines: StateFlow<Map<String, Long>> = _undoDeadlines.asStateFlow()

    private val undoJobs = mutableMapOf<String, Job>()

    private fun scheduleUndoable(key: String, action: suspend () -> Unit) {
        cancelUndoable(key)
        _undoDeadlines.value = _undoDeadlines.value + (key to System.currentTimeMillis() + UNDO_DELAY_MS)
        undoJobs[key] = viewModelScope.launch {
            delay(UNDO_DELAY_MS)
            _undoDeadlines.value = _undoDeadlines.value - key
            undoJobs.remove(key)
            action()
        }
    }

    /** Tapping the in-icon countdown (or Undo on the toast) cancels before submit. */
    fun cancelUndoable(key: String) {
        undoJobs.remove(key)?.cancel()
        _undoDeadlines.value = _undoDeadlines.value - key
    }

    /** The same capsule the in-app actions use, for a tip that went out without a sheet. */
    fun showTipToast(message: String, txId: String) = showActionToast(message, txId)

    /** Says what went wrong where the feed already says things, for a tip that did not send. */
    fun showFeedError(message: String) {
        _feedError.value = message
    }

    private fun showActionToast(message: String, txId: String) {
        val toast = ActionToast(message = message, txId = txId)
        _actionToast.value = toast
        viewModelScope.launch {
            delay(4_000)
            if (_actionToast.value?.id == toast.id) _actionToast.value = null
        }
    }

    /** A shared/linked post that nothing could resolve: the same capsule, with the explorer link
     *  so the reader can at least see the transaction (iOS). */
    fun showPostNotFound(txId: String) {
        showActionToast("Post not found - it may be older than the current feed", txId)
    }

    /** Memory, then the indexer, then the chain - the order every reopened draft resolves its
     *  reply or quote target in (iOS draftComposer). */
    suspend fun resolveAnyPost(txId: String): KaPostDraft? =
        findPostByRemoteId(txId) ?: indexerPost(txId) ?: chainPost(txId)

    /** Page one of the selected feed, only when nothing has been loaded for it yet. Coming back
     *  to the tab keeps what was on screen (iOS's KaPostsView stays alive across tab switches). */
    fun loadFeedIfNeeded() {
        val tab = _selectedFeed.value
        if (surfaceLoaded(feedKey(tab))) return
        viewModelScope.launch { loadFeed(tab) }
    }

    // MARK: - Identity chain (contact alias > KNS domain > shortened address; KNS owns display)

    /** Address -> KNS avatar URL (null value = fetched, none found). */
    private val _senderProfiles = MutableStateFlow<Map<String, String?>>(emptyMap())
    val senderProfiles: StateFlow<Map<String, String?>> = _senderProfiles.asStateFlow()

    /** Address -> active KNS domain name (null value = fetched, none owned). */
    private val _senderKnsNames = MutableStateFlow<Map<String, String?>>(emptyMap())
    val senderKnsNames: StateFlow<Map<String, String?>> = _senderKnsNames.asStateFlow()

    /** Address -> KNS profile banner URL / bio (null value = fetched, none set). */
    private val _senderBanners = MutableStateFlow<Map<String, String?>>(emptyMap())
    val senderBanners: StateFlow<Map<String, String?>> = _senderBanners.asStateFlow()
    private val _senderBios = MutableStateFlow<Map<String, String?>>(emptyMap())
    val senderBios: StateFlow<Map<String, String?>> = _senderBios.asStateFlow()

    /** Address -> locally-set contact alias; always wins over the KNS name. */
    val contactAliases: StateFlow<Map<String, String>> = chatRepository.getContacts()
        .map { contacts -> contacts.mapNotNull { c -> c.alias?.takeIf { it.isNotBlank() }?.let { c.id to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** A saved contact's own photo (linked phone contact, or the backup's photo), which every
     *  avatar in the app lets override the KNS avatar - iOS KNSAvatarView(contactAddress:). */
    data class ContactPhoto(val deviceContactPhotoUri: String?, val backupPhotoBase64: String?)

    val contactPhotos: StateFlow<Map<String, ContactPhoto>> = chatRepository.getContacts()
        .map { contacts ->
            contacts.mapNotNull { c ->
                if (c.systemContactPhotoUri.isNullOrBlank() && c.backupPhotoBase64.isNullOrBlank()) null
                else c.id to ContactPhoto(c.systemContactPhotoUri, c.backupPhotoBase64)
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Oldest-first cap for the per-address sender maps below — every address ever seen in
     *  any feed lands in them, and an infinite-scroll session grew them without bound (this
     *  codebase has been OOM-bitten by exactly this pattern before, see NodePoolManager). */
    private fun <V> Map<String, V>.cappedForSenders(): Map<String, V> =
        if (size <= 800) this else entries.drop(size - 600).associate { it.key to it.value }

    /**
     * Addresses already probed this session (in-flight or done) - a PLAIN set, not compose state.
     * The dedupe claim used to be written into [_senderProfiles] itself (address -> null), which
     * meant merely composing a feed row with a not-yet-seen author ticked the whole map StateFlow,
     * and every visible KaPostCell collects that map - so each new row scrolled in recomposed all
     * visible rows (feed scroll jank). Now the map only ticks when a real avatar URL arrives.
     */
    private val probedSenderProfiles = mutableSetOf<String>()

    /**
     * Caps how many identity probes run at once.
     *
     * Each probe is a KNS owned-domains call, a reverse resolve, and a profile fetch per owned
     * asset - so a handful of addresses is already a dozen requests. Every caller launched its
     * own coroutine with nothing holding them back: opening the composer fired one per contact
     * simultaneously (prefetchMentionCandidates), and a fast scroll fires one per row on top of
     * that. Four at a time keeps the KNS host from being hit with a burst, and costs nothing on
     * the small numbers that are the normal case. iOS bounds the same work at three.
     */
    private val senderProbeLimit = kotlinx.coroutines.sync.Semaphore(4)

    /**
     * Seeds the in-memory maps from disk, so a cold start opens with the names and avatars it
     * already knew instead of a feed of shortened addresses that fills in as requests land.
     */
    private fun seedSenderCachesFromDisk() {
        val cached = knsProfileCache.snapshot()
        if (cached.isEmpty()) return
        _senderKnsNames.value = cached.mapValues { it.value.knsName }.cappedForSenders()
        _senderProfiles.value = cached.mapValues { it.value.avatarUrl }.cappedForSenders()
        _senderBanners.value = cached.mapValues { it.value.bannerUrl }.cappedForSenders()
        _senderBios.value = cached.mapValues { it.value.bio }.cappedForSenders()
    }

    fun ensureSenderProfileFetched(address: String) {
        if (address.isEmpty()) return
        // A cached answer recent enough to still be true - the whole point of the disk cache.
        // Checked BEFORE the in-memory map so a seeded entry that has since gone stale is still
        // re-probed rather than being treated as settled for the life of the process.
        if (knsProfileCache.isFresh(address)) return
        if (_senderProfiles.value.containsKey(address) && knsProfileCache.entry(address) == null) return
        // Same unbounded-growth guard as cappedForSenders - a reset just re-allows a probe.
        if (probedSenderProfiles.size > 4000) probedSenderProfiles.clear()
        if (!probedSenderProfiles.add(address)) return
        viewModelScope.launch {
            senderProbeLimit.withPermit {
            try {
                // A lookup that could not be completed caches nothing: "no KNS" below is a real
                // answer, and a dropped connection must not be written down as one.
                val ownedAssets = knsService.getOwnedDomainsOrNull(address) ?: return@withPermit
                if (ownedAssets.isEmpty()) {
                    // "This address has no KNS" is a real answer, and re-asking for it on every
                    // launch was the most common wasted call of the lot. Cached, with a shorter
                    // life than a positive one since a domain can be inscribed at any time.
                    knsProfileCache.put(address, null, null, null, null)
                    return@withPermit
                }
                val ownedNames = ownedAssets.mapNotNull { it.asset }
                val primary = knsService.reverseResolve(address)
                val activeName = KnsService.pickActiveDomain(ownedNames, null, primary)
                _senderKnsNames.value = (_senderKnsNames.value + (address to activeName)).cappedForSenders()
                val activeAsset = ownedAssets.firstOrNull { it.asset == activeName }
                val checkOrder = listOfNotNull(activeAsset) + ownedAssets.filterNot { it.asset == activeName }
                for (asset in checkOrder) {
                    val profile = asset.assetId?.let { knsService.getProfile(it) } ?: continue
                    if (_senderProfiles.value[address] == null && profile.avatarUrl != null) {
                        _senderProfiles.value = (_senderProfiles.value + (address to profile.avatarUrl)).cappedForSenders()
                    }
                    if (_senderBanners.value[address] == null && profile.bannerUrl != null) {
                        _senderBanners.value = (_senderBanners.value + (address to profile.bannerUrl)).cappedForSenders()
                    }
                    if (_senderBios.value[address] == null && !profile.bio.isNullOrBlank()) {
                        _senderBios.value = (_senderBios.value + (address to profile.bio)).cappedForSenders()
                    }
                    if (_senderProfiles.value[address] != null && _senderBanners.value[address] != null) break
                }
                // One write per probe, not per field: four writes for one answer would be four
                // serialisations of the whole map.
                knsProfileCache.put(
                    address = address,
                    knsName = _senderKnsNames.value[address],
                    avatarUrl = _senderProfiles.value[address],
                    bannerUrl = _senderBanners.value[address],
                    bio = _senderBios.value[address],
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch KNS profile for $address", e)
            }
            }
        }
    }

    /**
     * A name as it is SHOWN. A KNS domain keeps its ".kas" everywhere: the suffix is part of the
     * name, and KaPosts used to be the one place that dropped it. Aliases pass through exactly
     * as the user wrote them.
     */
    fun displayKasName(name: String): String = name.trim()

    /**
     * A domain as a LOOKUP KEY: lowercased, ".kas" off. Typing and matching never need the
     * suffix - it is the only one there is - so mention queries, the candidate map and KNS
     * resolution all work on this form.
     */
    fun bareKasName(domain: String): String {
        val trimmed = domain.trim().lowercase()
        return if (trimmed.endsWith(".kas")) trimmed.dropLast(4) else trimmed
    }

    /** A domain as shown and as typed into a post: always with ".kas". */
    fun fullKasName(domain: String): String = bareKasName(domain).let { if (it.isEmpty()) it else "$it.kas" }

    /** Contact alias > KNS domain > shortened address. */
    // ------------------------------------------------------------------
    // Search
    //
    // CLIENT-SIDE, because the K indexer has no search endpoint - every route it has is a feed
    // or a lookup by id (see KAPOSTS_INDEXER.md). So this pages the global feed and filters what
    // comes back, which has one honest consequence the UI states rather than hides: it searches
    // as far back as it has paged, not the whole chain.
    //
    // People are derived from the AUTHORS of the posts it scans, which is what makes "only
    // people who have posted at least once" true by construction rather than by a filter that
    // could be wrong: an address is only ever offered because a post of theirs was read.
    // ------------------------------------------------------------------

    /** One person in the People results, with how many of their scanned posts matched. */
    data class SearchPerson(val address: String, val postCount: Int)

    private val _searchScanned = MutableStateFlow<List<KaPostDraft>>(emptyList())
    /** How many posts the search has read so far - shown so "nothing found" is honest. */
    val searchScannedCount: StateFlow<Int> =
        _searchScanned.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var searchCursor: String? = null
    private val _searchHasMore = MutableStateFlow(true)
    val searchHasMore: StateFlow<Boolean> = _searchHasMore.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    /** The last read of older posts failed (iOS loadFailed): the footer says so. */
    private val _searchLoadFailed = MutableStateFlow(false)
    val searchLoadFailed: StateFlow<Boolean> = _searchLoadFailed.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    fun setSearchQuery(value: String) { _searchQuery.value = value }

    /** Posts matching the query, by their text OR their author's name - so searching a person
     *  finds their posts without having to switch tabs to find them first. */
    val searchPostResults: StateFlow<List<KaPostDraft>> =
        combine(_searchScanned, _searchQuery, combine(muted, blocked) { m, b -> m + b }) { scanned, query, hidden ->
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return@combine emptyList()
            scanned.filter { post ->
                post.posterAddress !in hidden &&
                    (post.text.lowercase().contains(needle) ||
                        posterDisplayName(post.posterAddress).lowercase().contains(needle))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Authors of scanned posts whose name or address matches, busiest on this term first. */
    val searchPeopleResults: StateFlow<List<SearchPerson>> =
        combine(_searchScanned, _searchQuery, combine(muted, blocked) { m, b -> m + b }) { scanned, query, hidden ->
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return@combine emptyList()
            scanned.asSequence()
                .filter { it.posterAddress.isNotEmpty() && it.posterAddress !in hidden }
                .groupingBy { it.posterAddress }
                .eachCount()
                .filter { (address, _) ->
                    posterDisplayName(address).lowercase().contains(needle) ||
                        address.lowercase().contains(needle)
                }
                .map { (address, count) -> SearchPerson(address, count) }
                .sortedWith(compareByDescending<SearchPerson> { it.postCount }
                    .thenBy { posterDisplayName(it.address) })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Reads another stretch of the global feed into the searchable set. */
    fun searchLoadMore() {
        if (_isSearching.value || !_searchHasMore.value) return
        _isSearching.value = true
        viewModelScope.launch {
            try {
                val result = accumulate(
                    startCursor = searchCursor,
                    target = TARGET_NEW_ROWS,
                    seenIds = _searchScanned.value.mapNotNull { it.remoteId }.toSet(),
                    idOf = KPost::id,
                    map = { mapRemotePost(it) },
                    isVisible = { true },
                    fetch = { before -> kaPostsService.fetchGlobalFeedPage(PAGE_LIMIT, before) },
                )
                _searchScanned.value = _searchScanned.value + result.items
                searchCursor = result.cursor
                _searchLoadFailed.value = result.error != null && result.items.isEmpty()
                _searchHasMore.value = if (result.error != null) true else result.hasMore
                // Warm the names so People rows are not a wall of shortened addresses. Bounded
                // by the probe semaphore, and skipped for anything already cached on disk.
                for (address in result.items.map { it.posterAddress }.distinct()) {
                    ensureSenderProfileFetched(address)
                }
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun posterDisplayName(address: String): String {
        if (address.isEmpty()) return "Unknown"
        contactAliases.value[address]?.takeIf { it.isNotBlank() }?.let { return displayKasName(it) }
        _senderKnsNames.value[address]?.takeIf { it.isNotBlank() }?.let { return displayKasName(it) }
        return address.takeLast(10)
    }

    fun myAddress(): String? = try { walletManager.getAddress() } catch (_: Exception) { null }

    // MARK: - Feed loading

    /** Feed and Popular share the global stream (and therefore one cursor); Following owns its own. */
    private fun feedKey(tab: FeedTab): String =
        if (tab == FeedTab.FOLLOWING) PAGE_FOLLOWING_FEED else PAGE_GLOBAL_FEED

    private fun feedFlow(tab: FeedTab): MutableStateFlow<List<KaPostDraft>> =
        if (tab == FeedTab.FOLLOWING) _followingPosts else _globalPosts

    private suspend fun fetchFeedPage(tab: FeedTab, before: String?) =
        if (tab == FeedTab.FOLLOWING) {
            kaPostsService.fetchFollowingFeedPage(PAGE_LIMIT, before)
        } else {
            kaPostsService.fetchGlobalFeedPage(PAGE_LIMIT, before)
        }

    /** Would this row actually render in [tab]? Mirrors the visible-feed flows exactly. */
    private fun isFeedRowVisible(tab: FeedTab, post: KaPostDraft): Boolean {
        if (isHidden(post.posterAddress)) return false
        return tab != FeedTab.FOLLOWING || post.posterAddress in following.value
    }

    /**
     * Selecting a tab no longer refetches it: pages already accumulated (and the scroll position
     * riding on them) must survive a swipe away and back. Only a never-loaded tab loads here; the
     * Refresh control is what resets to page one.
     */
    /** A post's popularity score. See the ordering in [feedFor]. */
    private fun popularityScore(post: KaPostDraft): Int =
        post.likes + post.reposts + post.dislikes + commentCount(post)

    /**
     * Pulls the global feed until Popular has [POPULAR_RANKING_DEPTH] posts to rank, so its top
     * row is the most popular post in a real window of history rather than the most popular of
     * whatever page one happened to contain.
     *
     * Runs only while Popular is the selected tab, and re-checks the surface generation between
     * passes, so a tab switch, a refresh or an account change stops it instead of paging a feed
     * the user has left.
     */
    // MARK: - Load gates (iOS FeedLoadGates)
    //
    // Session bookkeeping for the loads that are worth NOT repeating. Anything loaded from page
    // one within this window is current enough to search in place of re-fetching it - a reader's
    // scrolled pages survive a shared link - and Popular's deep sweep runs at most once per
    // window unless the feed was refreshed from page one in between.
    private val loadGateStaleAfterMs = 5 * 60_000L
    private var feedLoadedAt = 0L
    private var popularSweptAt = 0L
    private var myProfileLoadedAt = 0L
    private var posterProfileLoaded: Pair<String, Long>? = null

    private fun isLoadFresh(at: Long): Boolean = at > 0L && System.currentTimeMillis() - at < loadGateStaleAfterMs

    private fun deepenPopularRanking() {
        val key = PAGE_GLOBAL_FEED
        if (popularSweepJob?.isActive == true) return
        // A heavily-filtered stretch of history never reaches the ranking depth, and without
        // this every return to the tab re-spent the full request budget chasing it.
        if (isLoadFresh(popularSweptAt)) return
        popularSweptAt = System.currentTimeMillis()
        popularSweepJob = viewModelScope.launch {
            var passes = 0
            while (
                _globalPosts.value.size < POPULAR_RANKING_DEPTH &&
                passes < POPULAR_SWEEP_MAX_PASSES &&
                _selectedFeed.value == FeedTab.POPULAR
            ) {
                passes++
                val generation = generations[key] ?: 0
                val state = pagingState(key)
                if (state.isLoadingMore || !state.hasMore || _isLoadingFeed.value) return@launch
                updatePaging(key) { it.copy(isLoadingMore = true, error = null) }
                val result = accumulate(
                    startCursor = state.cursor,
                    target = POPULAR_RANKING_DEPTH,
                    seenIds = _globalPosts.value.mapNotNull { it.remoteId }.toSet(),
                    maxRequests = POPULAR_SWEEP_REQUESTS_PER_PASS,
                    idOf = KPost::id,
                    map = { mapRemotePost(it) },
                    isVisible = { isFeedRowVisible(FeedTab.POPULAR, it) },
                    fetch = { before -> fetchFeedPage(FeedTab.POPULAR, before) },
                )
                if (generations[key] != generation) return@launch
                if (result.items.isNotEmpty()) {
                    _globalPosts.value = appendUnique(_globalPosts.value, result.items)
                }
                updatePaging(key) {
                    it.copy(
                        cursor = result.cursor,
                        hasMore = if (result.error != null) it.hasMore else result.hasMore,
                        isLoadingMore = false,
                        error = result.error,
                    )
                }
                // A pass that added nothing (everything filtered out, or the fetch failed) would
                // otherwise spin against the same cursor.
                if (result.items.isEmpty() || result.error != null || !result.hasMore) return@launch
            }
        }
    }

    fun selectFeed(tab: FeedTab) {
        _selectedFeed.value = tab
        if (!surfaceLoaded(feedKey(tab))) {
            viewModelScope.launch {
                loadFeed(tab)
                if (tab == FeedTab.POPULAR) deepenPopularRanking()
            }
        } else if (tab == FeedTab.POPULAR) {
            deepenPopularRanking()
        }
    }

    /**
     * Page one for [tab]: clears the accumulated pages, the cursor and the end-reached flag.
     *
     * The in-flight guard is PER TAB (the surface's own flag), not the shared header spinner -
     * swiping to Following while the global feed is still loading has to be able to load
     * Following, and the two write to different lists anyway.
     */
    /**
     * One-shot per session: rebuild the LOCAL follow set from the on-chain follow graph.
     * The local set is what every Follow button and the Following feed filter read, and it
     * lives only in DataStore — so an upgrade/reinstall that clears app data left users
     * "following 0 friends" with Follow buttons beside people they already follow on-chain.
     * Chain entries are only ever ADDED (never removed), so a just-tapped local unfollow the
     * indexer hasn't caught up on can't be resurrected mid-session.
     */
    private var followingChainSyncStarted = false
    fun syncFollowingFromChain() {
        if (followingChainSyncStarted) return
        followingChainSyncStarted = true
        // Capture the account being synced NOW. The whole sync stays pinned to it: pubkey is
        // verified against it and the merge writes into ITS scoped key, so an account switch
        // mid-flight can never import the old account's on-chain follows into the new one.
        val walletAddress = myAddress() ?: run { followingChainSyncStarted = false; return }
        viewModelScope.launch {
            try {
                val pubkey = kaPostsService.requesterPubkey()
                if (KaPostsService.kaspaAddressFromPubkey(pubkey) != walletAddress) {
                    // Wallet switched between capture and derivation - the switch collector in
                    // init re-arms the sync for the new account.
                    followingChainSyncStarted = false
                    return@launch
                }
                val chain = mutableSetOf<String>()
                var cursor: String? = null
                var pagesLeft = 10 // up to 10 pages of 100 — far beyond any real follow list (iOS)
                while (pagesLeft-- > 0) {
                    val page = kaPostsService.fetchFollowListPage(pubkey, followers = false, FOLLOW_PAGE_LIMIT, cursor)
                    page.items.forEach { user ->
                        KaPostsService.kaspaAddressFromPubkey(user.userPublicKey)?.let { chain += it }
                    }
                    cursor = page.cursor
                    if (!page.hasMore || cursor == null) break
                }
                // Read + write the CAPTURED account's scoped set (not following.value, which
                // tracks whatever account is active by the time the fetch loop finishes).
                val local = settings.kapostsFollowing(walletAddress).first()
                val merged = local + chain - walletAddress
                if (merged != local) settings.setKapostsFollowing(walletAddress, merged)
            } catch (e: Exception) {
                followingChainSyncStarted = false // network miss — retry on the next feed load
                Log.w(TAG, "Follow-set chain sync failed", e)
            }
        }
    }

    init {
        // Before anything else: open with what KNS told us last time this app ran, rather than
        // a feed of shortened addresses that fills in as requests land.
        seedSenderCachesFromDisk()
        viewModelScope.launch {
            // Strictly per-account follow state, part 2: on an account switch, re-arm the
            // one-shot chain sync so the NEW account's on-chain follow graph is imported into
            // ITS scoped key on the next feed load (`following` above already swaps the
            // persisted key itself via flatMapLatest).
            walletManager.activeAddressFlow.drop(1).collect {
                followingChainSyncStarted = false
                // One account's reading history must not linger on screen under another's feed.
                resetTranslations()
                // Account switch: nothing loaded belongs to the new identity.
                feedLoadedAt = 0L
                popularSweptAt = 0L
                myProfileLoadedAt = 0L
                posterProfileLoaded = null
            }
        }
        // One-time cleanup of the legacy GLOBAL follow set, which leaked one account's follows
        // into every other account on this device (fresh accounts started with them).
        viewModelScope.launch { settings.clearLegacyKapostsFollowing() }
    }

    suspend fun loadFeed(tab: FeedTab = _selectedFeed.value) {
        syncFollowingFromChain()
        val key = feedKey(tab)
        if (pagingState(key).isLoadingMore) return
        val generation = resetSurface(key)
        updatePaging(key) { it.copy(isLoadingMore = true) }
        val isSelected = tab == _selectedFeed.value
        if (isSelected) {
            _isLoadingFeed.value = true
            _feedError.value = null
        }
        try {
            val result = accumulate(
                startCursor = null,
                target = TARGET_NEW_ROWS,
                seenIds = emptySet(),
                idOf = KPost::id,
                map = { mapRemotePost(it) },
                isVisible = { isFeedRowVisible(tab, it) },
                fetch = { before -> fetchFeedPage(tab, before) },
            )
            if (generations[key] != generation) return
            if (result.error != null && result.items.isEmpty()) {
                if (isSelected) _feedError.value = result.error
            } else {
                feedFlow(tab).value = result.items
                // A refresh just delivered whatever was being offered; leaving the pill up would
                // promise posts that are already on screen.
                clearPendingNewPosts()
                if (key == PAGE_GLOBAL_FEED) {
                    // Page one is fresh again, and a refresh re-arms Popular's deep sweep: the
                    // window it ranked was just thrown away.
                    feedLoadedAt = System.currentTimeMillis()
                    popularSweptAt = 0L
                }
            }
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false,
                    error = null, stalled = result.stalled,
                )
            }
        } finally {
            if (isSelected) _isLoadingFeed.value = false
            if (pagingState(key).isLoadingMore && generations[key] == generation) {
                updatePaging(key) { it.copy(isLoadingMore = false) }
            }
        }
    }

    /**
     * Endless scroll for a feed tab. Safe to call on every scroll frame - it is a no-op while a
     * load is in flight, while page one is loading, and once the end has been reached.
     */
    fun loadMoreFeed(tab: FeedTab, manual: Boolean = false) {
        val key = feedKey(tab)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || _isLoadingFeed.value) return
        if (state.stalled && !manual) return
        if (!surfaceLoaded(key)) return
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null, stalled = false) }
        loadMoreJobs[key] = viewModelScope.launch {
            val flow = feedFlow(tab)
            val result = accumulate(
                startCursor = state.cursor,
                target = TARGET_NEW_ROWS,
                seenIds = flow.value.mapNotNull { it.remoteId }.toSet(),
                idOf = KPost::id,
                map = { mapRemotePost(it) },
                isVisible = { isFeedRowVisible(tab, it) },
                fetch = { before -> fetchFeedPage(tab, before) },
            )
            if (generations[key] != generation) return@launch
            if (result.items.isNotEmpty()) flow.value = appendUnique(flow.value, result.items)
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    // A failed load-more keeps the list and stays "has more" so retry can resume.
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
                    stalled = result.stalled,
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    /** Append guard: ids are stable (stableId of the txid), so a row the list already holds
     *  arriving again - any page-one/load-more interleave - must drop rather than duplicate,
     *  or LazyColumn's unique-key contract crashes the app. */
    private fun appendUnique(held: List<KaPostDraft>, incoming: List<KaPostDraft>): List<KaPostDraft> {
        if (incoming.isEmpty()) return held
        val ids = held.mapTo(HashSet()) { it.id }
        return held + incoming.filterNot { it.id in ids }
    }

    fun refresh() {
        viewModelScope.launch { loadFeed() }
    }

    /** K wire post -> UI model. Content arrives base64-decoded with the marker stripped. */
    fun mapRemotePost(post: KPost): KaPostDraft? {
        val content = post.decodedContent ?: return null
        val address = KaPostsService.kaspaAddressFromPubkey(post.userPublicKey) ?: return null
        val quoted = post.quote?.let { q ->
            val quotedText = q.decodedMessage
            val quotedAddress = q.referencedSenderPubkey?.let { KaPostsService.kaspaAddressFromPubkey(it) }
            if (quotedText != null && quotedAddress != null) {
                KaPostDraft.QuotedRef(
                    remoteId = q.referencedContentId,
                    text = com.kachat.app.util.KaPostsProtocol.stripMarker(quotedText),
                    posterAddress = quotedAddress,
                    timestamp = null,
                )
            } else null
        }
        return KaPostDraft(
            id = KaPostDraft.stableId(post.id),
            text = com.kachat.app.util.KaPostsProtocol.stripMarker(content),
            timestamp = post.timestamp,
            posterAddress = address,
            remoteId = post.id,
            posterPubkey = post.userPublicKey,
            likes = post.upVotesCount ?: 0,
            dislikes = post.downVotesCount ?: 0,
            reposts = post.quotesCount ?: 0,
            likedByMe = post.isUpvoted ?: false,
            dislikedByMe = post.isDownvoted ?: false,
            remoteReplyCount = post.repliesCount ?: 0,
            quoted = quoted,
            parentRemoteId = post.parentPostId,
            editedAt = post.editedAt,
            poll = post.poll?.let { remote ->
                val options = remote.decodedOptions
                if (options.isEmpty()) null else KaPostDraft.KaPostPoll(
                    options = options,
                    counts = List(options.size) { index -> remote.counts?.getOrNull(index) ?: 0 },
                    closesAtMs = remote.closesAt ?: 0L,
                    myVote = remote.myVote,
                )
            },
        )
    }

    // MARK: - Post tree mutation (local + remote lists; profile lists arrive in Phase B)

    /** Every list a post can live in, in iOS mutatePost's search order. */
    /**
     * Posts read off the chain because the indexer could not answer for them (see [chainPost]).
     *
     * They belong to no feed and are never rendered as one - they live here only so the thread
     * opened onto them can FIND them. The overlay is handed an id, not a post, and resolves it
     * against these lists; a post in none of them made the overlay close itself on the spot, so
     * the tap looked like it had done nothing.
     */
    private val _chainPosts = MutableStateFlow<List<KaPostDraft>>(emptyList())

    private fun allPostLists(): List<MutableStateFlow<List<KaPostDraft>>> = listOf(
        _localPosts, _globalPosts, _followingPosts, _posterProfilePosts, _posterProfileReplies,
        _myProfilePosts, _myProfileReplies, _chainPosts,
    )

    /**
     * Applies [transform] to EVERY copy of the post, in every list AND the self-thread chains.
     *
     * It used to stop at the first list that held the id, but the same post (stable txid ids)
     * lives in several lists at once - the global feed and a profile tab, a standalone reply row
     * and the same reply nested under its parent's comments - and the copy an open thread
     * renders is resolved through its ROOT's tree, which is not necessarily where a first-hit
     * search by the comment's own id lands. A like inside an open thread then mutated some other
     * copy and the thread never repainted. Mutating every copy keeps all surfaces consistent;
     * lists without an occurrence keep their instance (mutatePostIn returns them reference-
     * equal) so their flows never tick.
     */
    private fun mutateEverywhere(id: String, transform: (KaPostDraft) -> KaPostDraft) {
        for (flow in allPostLists()) {
            val (updated, hit) = mutatePostIn(flow.value, id, transform)
            if (hit) flow.value = updated
        }
        // The X-style self-thread chain renders its segments inside open threads, but lives in
        // its own map OUTSIDE the post lists - without this, liking a chain segment applied to
        // nothing at all and only showed up after a reopen refetched the chain from the indexer.
        val chains = _threadChains.value
        var chainsChanged = false
        val updatedChains = chains.mapValues { (_, segments) ->
            val (updated, hit) = mutatePostIn(segments, id, transform)
            if (hit) chainsChanged = true
            updated
        }
        if (chainsChanged) _threadChains.value = updatedChains
    }

    fun findPost(id: String): KaPostDraft? =
        allPostLists().firstNotNullOfOrNull { findPostIn(it.value, id) }

    fun findPostByRemoteId(remoteId: String): KaPostDraft? =
        allPostLists().firstNotNullOfOrNull { findPostByRemoteIdIn(it.value, remoteId) }

    /** Recursive parent lookup: who owns this comment, at any nesting depth. */
    fun findParent(ofCommentId: String): KaPostDraft? {
        fun search(list: List<KaPostDraft>): KaPostDraft? {
            for (post in list) {
                if (post.comments.any { it.id == ofCommentId }) return post
                search(post.comments)?.let { return it }
            }
            return null
        }
        return allPostLists().firstNotNullOfOrNull { search(it.value) }
    }

    // MARK: - Posting (optimistic insert + 5s undo, then on-chain submit)

    fun schedulePost(text: String) {
        val myAddress = myAddress() ?: return
        val newPost = KaPostDraft(
            text = text,
            timestamp = System.currentTimeMillis(),
            posterAddress = myAddress,
            posterPubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { null },
            deliveryStatus = KaPostDraft.Delivery.PENDING,
        )
        _localPosts.value = listOf(newPost) + _localPosts.value
        val key = "post:${newPost.id}"
        _undoToast.value = UndoToast(key, newPost.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Posting", draftText = text)
        scheduleUndoable(key) {
            clearUndoToast(key)
            submitScheduledPost(newPost.id, text)
        }
    }

    private fun clearUndoToast(key: String) {
        if (_undoToast.value?.key == key) _undoToast.value = null
    }

    fun undoPendingPost() {
        val toast = _undoToast.value ?: return
        cancelUndoable(toast.key)
        // Only compose-style actions have an optimistic CARD to remove; a cancelled
        // like/dislike/repost simply never happens (their mutation fires post-countdown),
        // and a cancelled comment removes its optimistic reply from the thread.
        when {
            toast.key.startsWith("post:") ->
                _localPosts.value = _localPosts.value.filterNot { it.id == toast.postId }
            toast.key.startsWith("comment:") -> removeReplyEverywhere(toast.postId)
            // An undone delete simply lifts the dimming; nothing was sent.
            toast.key.startsWith("delete:") ->
                mutateEverywhere(toast.postId) { it.copy(pendingDeletion = false) }
            // An undone edit puts the previous text back exactly as it was.
            toast.key.startsWith("edit:") -> pendingEditOriginals.remove(toast.postId)?.let { (text, editedAt) ->
                mutateEverywhere(toast.postId) { it.copy(text = text, editedAt = editedAt, deliveryStatus = KaPostDraft.Delivery.SENT) }
            }
        }
        _undoToast.value = null
        // Hand the words back so the five seconds are a chance to fix something rather than a
        // chance to lose it. Carried on the toast rather than read off the optimistic card,
        // which was removed in the same breath.
        toast.draftText?.takeIf { it.isNotEmpty() }?.let { text ->
            // Split a thread back the way the composer holds it: every segment but the last is
            // a stacked segment, the last is what was in the editor when Post All was pressed.
            val segments = toast.draftSegments.orEmpty()
            _restoredDraft.value = RestoredDraft(
                text = text,
                quoteTargetId = toast.quoteTargetId,
                isComment = toast.key.startsWith("comment:"),
                threadSegments = if (segments.size > 1) segments.dropLast(1) else emptyList(),
                commentParentId = toast.commentParentId,
            )
        }
    }

    /**
     * Replaces the text of one of our own posts, replies or quotes. The new words show at once,
     * behind the same five-second countdown as every other action, and then go on chain. Undo
     * puts the old text back, and so does a transaction that fails.
     *
     * Only inside the edit window, only our own on-chain content: the indexer enforces the same
     * rules, so anything else would be written to the chain and then ignored.
     */
    fun editPost(post: KaPostDraft, newText: String) {
        val trimmed = newText.trim()
        val remoteId = post.remoteId ?: return
        if (trimmed.isEmpty() || trimmed == post.text) return
        if (post.editTimeRemainingMs == null) return
        if (post.posterAddress != myAddress()) return

        pendingEditOriginals[post.id] = post.text to post.editedAt
        mutateEverywhere(post.id) {
            it.copy(text = trimmed, editedAt = System.currentTimeMillis(), deliveryStatus = KaPostDraft.Delivery.PENDING)
        }
        val key = "edit:${post.id}"
        _undoToast.value = UndoToast(key, post.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Saving edit")
        scheduleUndoable(key) {
            clearUndoToast(key)
            val original = pendingEditOriginals.remove(post.id)
            try {
                kaPostsService.submitEdit(trimmed, remoteId, mentionedPubkeys(trimmed))
                mutateEverywhere(post.id) {
                    it.copy(deliveryStatus = KaPostDraft.Delivery.SENT, sentAt = System.currentTimeMillis())
                }
            } catch (e: Exception) {
                mutateEverywhere(post.id) {
                    it.copy(
                        text = original?.first ?: it.text,
                        editedAt = original?.second,
                        deliveryStatus = KaPostDraft.Delivery.SENT,
                    )
                }
                _feedError.value = "Couldn't save the edit: ${e.message}"
                Log.w(TAG, "Edit submit failed", e)
            }
        }
    }

    /** Strips an optimistic comment out of every post tree that holds it —
     *  the same collections mutateEverywhere() searches. */
    private fun removeReplyEverywhere(commentId: String) {
        // Undone before it was ever posted: nothing left to hold at the top of the thread.
        sessionReplyIds.remove(commentId)
        fun strip(list: List<KaPostDraft>): List<KaPostDraft> = list.map { post ->
            post.copy(comments = strip(post.comments.filterNot { it.id == commentId }))
        }
        for (flow in allPostLists()) flow.value = strip(flow.value)
    }

    /**
     * Deletes one of our own posts, replies or quotes, at any age. The card dims behind the same
     * five-second countdown as every other action - Undo simply lifts it - and after that the
     * delete goes on chain and the post leaves every list and every comment tree.
     */
    fun deletePost(post: KaPostDraft) {
        val remoteId = post.remoteId ?: return
        if (post.deliveryStatus != KaPostDraft.Delivery.SENT || post.pendingDeletion) return
        if (post.posterAddress != myAddress()) return

        mutateEverywhere(post.id) { it.copy(pendingDeletion = true) }
        val key = "delete:${post.id}"
        _undoToast.value = UndoToast(key, post.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Deleting post")
        scheduleUndoable(key) {
            clearUndoToast(key)
            try {
                kaPostsService.submitDelete(remoteId)
                removePostEverywhere(post.id)
            } catch (e: Exception) {
                mutateEverywhere(post.id) { it.copy(pendingDeletion = false) }
                _feedError.value = "Couldn't delete the post: ${e.message}"
                Log.w(TAG, "Delete submit failed", e)
            }
        }
    }

    /** Drops every node with this id from every list and every comment tree - a post can be a
     *  feed card and a comment at once. */
    private fun removePostEverywhere(id: String) {
        fun strip(list: List<KaPostDraft>): List<KaPostDraft> =
            list.filterNot { it.id == id }.map { it.copy(comments = strip(it.comments)) }
        for (flow in allPostLists()) flow.value = strip(flow.value)
    }

    private suspend fun submitScheduledPost(localId: String, text: String) {
        try {
            val txId = kaPostsService.submitPost(text, mentionedPubkeys(text))
            mutateEverywhere(localId) { it.copy(remoteId = txId, deliveryStatus = KaPostDraft.Delivery.SENT) }
        } catch (e: Exception) {
            mutateEverywhere(localId) { it.copy(deliveryStatus = KaPostDraft.Delivery.FAILED) }
            Log.w(TAG, "Post submit failed", e)
        }
    }

    // MARK: - Polls (KAPOSTS_INDEXER.md section 5.9)

    /**
     * Posts a poll: the question is the post itself, the options and the closing time ride
     * alongside it. Optimistic card first, behind the same five-second undo every other compose
     * action gets, then one transaction.
     */
    fun createPoll(question: String, options: List<String>, closesAtMs: Long) {
        val myAddress = myAddress() ?: return
        val clean = options.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.size < KaPostsService.POLL_MIN_OPTIONS || clean.size > KaPostsService.POLL_MAX_OPTIONS) return
        val newPost = KaPostDraft(
            text = question,
            timestamp = System.currentTimeMillis(),
            posterAddress = myAddress,
            posterPubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { null },
            deliveryStatus = KaPostDraft.Delivery.PENDING,
            poll = KaPostDraft.KaPostPoll(
                options = clean,
                counts = List(clean.size) { 0 },
                closesAtMs = closesAtMs,
                myVote = null,
            ),
        )
        _localPosts.value = listOf(newPost) + _localPosts.value
        val key = "post:${newPost.id}"
        _undoToast.value = UndoToast(key, newPost.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Posting poll", draftText = question)
        scheduleUndoable(key) {
            clearUndoToast(key)
            try {
                val txId = kaPostsService.submitPoll(question, clean, closesAtMs, mentionedPubkeys(question))
                mutateEverywhere(newPost.id) { it.copy(remoteId = txId, deliveryStatus = KaPostDraft.Delivery.SENT) }
            } catch (e: Exception) {
                mutateEverywhere(newPost.id) { it.copy(deliveryStatus = KaPostDraft.Delivery.FAILED) }
                Log.w(TAG, "Poll submit failed", e)
            }
        }
    }

    /**
     * Votes in a poll. The card takes the choice at once (one vote per person, so a second vote
     * moves the first), behind the usual five seconds, and then one transaction carries it. The
     * indexer's own numbers replace the optimistic ones as soon as it has counted the vote.
     */
    fun votePoll(post: KaPostDraft, optionIndex: Int) {
        val poll = post.poll ?: return
        val remoteId = post.remoteId ?: return
        if (poll.isClosed || optionIndex !in poll.options.indices) return
        val previous = poll.myVote
        if (previous == optionIndex) return
        mutateEverywhere(post.id) { current ->
            val live = current.poll ?: return@mutateEverywhere current
            val counts = live.counts.toMutableList()
            if (previous != null && previous in counts.indices) counts[previous] = (counts[previous] - 1).coerceAtLeast(0)
            if (optionIndex in counts.indices) counts[optionIndex] = counts[optionIndex] + 1
            current.copy(poll = live.copy(counts = counts, myVote = optionIndex))
        }
        val key = "pollvote:${post.id}"
        scheduleUndoable(key) {
            try {
                kaPostsService.submitPollVote(remoteId, optionIndex)
                refreshPoll(post.id, remoteId)
            } catch (e: Exception) {
                Log.w(TAG, "Poll vote failed", e)
                // Put the card back the way it was: the vote never reached the chain.
                mutateEverywhere(post.id) { current ->
                    val live = current.poll ?: return@mutateEverywhere current
                    val counts = live.counts.toMutableList()
                    if (optionIndex in counts.indices) counts[optionIndex] = (counts[optionIndex] - 1).coerceAtLeast(0)
                    if (previous != null && previous in counts.indices) counts[previous] = counts[previous] + 1
                    current.copy(poll = live.copy(counts = counts, myVote = previous))
                }
            }
        }
    }

    /** Takes one poll's numbers from the indexer, without reloading a feed. */
    fun refreshPoll(localId: String, remoteId: String) {
        viewModelScope.launch {
            val remote = try { kaPostsService.fetchPoll(remoteId) } catch (_: Exception) { null } ?: return@launch
            val options = remote.decodedOptions
            if (options.isEmpty()) return@launch
            mutateEverywhere(localId) { current ->
                current.copy(
                    poll = KaPostDraft.KaPostPoll(
                        options = options,
                        counts = List(options.size) { index -> remote.counts?.getOrNull(index) ?: 0 },
                        closesAtMs = remote.closesAt ?: current.poll?.closesAtMs ?: 0L,
                        myVote = remote.myVote ?: current.poll?.myVote,
                    )
                )
            }
        }
    }

    // MARK: - @mentions (client-resolved: @domain -> contact address -> compressed pubkey; the
    // indexer turns each pubkey in mentioned_pubkeys into a "mention" notification)

    /** Mentionable = your 1:1 contacts with a KNS domain and a derivable pubkey: (bareDomain, pubkey). */
    // Memoized against the inputs that can change it — the composer calls this per KEYSTROKE
    // while an @ token is active, and the unmemoized version decoded an address + derived a
    // pubkey for every contact on the main thread each time.
    private var mentionCandidatesCache: Triple<Map<String, String>, Map<String, String?>, List<Pair<String, String>>>? = null
    fun mentionCandidates(): List<Pair<String, String>> {
        val aliases = contactAliases.value
        val knsNames = _senderKnsNames.value
        mentionCandidatesCache?.let { (a, k, cached) ->
            if (a === aliases && k === knsNames) return cached
        }
        val out = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        for ((address, _) in aliases) {
            val domain = knsNames[address]?.takeIf { it.isNotBlank() } ?: continue
            val bare = bareKasName(domain)
            if (bare.isEmpty() || bare in seen) continue
            val pubkey = KaPostsService.kapostPubkeyFromAddress(address) ?: continue
            seen.add(bare)
            out.add(bare to pubkey)
        }
        mentionCandidatesCache = Triple(aliases, knsNames, out)
        return out
    }

    /** Kick KNS lookups for every 1:1 contact so the @ autocomplete has domains to offer. */
    fun prefetchMentionCandidates() {
        for ((address, _) in contactAliases.value) ensureSenderProfileFetched(address)
    }

    /** The bare @domain tokens in `text`, in order, deduped. */
    private fun mentionDomains(text: String): List<String> {
        val out = linkedSetOf<String>()
        for (match in MENTION_TOKEN_REGEX.findAll(text)) {
            var domain = match.groupValues[2].lowercase()
            if (domain.endsWith(".kas")) domain = domain.dropLast(4)
            if (domain.isNotEmpty()) out.add(domain)
        }
        return out.toList()
    }

    /**
     * Resolves every @domain in `text` to a compressed pubkey for mentioned_pubkeys. Chatted
     * contacts resolve locally; ANYONE else with a KNS domain resolves live (owner address ->
     * pubkey). Unresolvable tokens stay plain text.
     */
    private suspend fun mentionedPubkeys(text: String): List<String> {
        // Scanned on the RENDERED text, not the source. The @ token has to start a word, so
        // "**@alice.kas**" hides the mention behind the bold markers: the reader would see a
        // highlighted, tappable mention (the cell renders the same rendered text) while the
        // signed mentions array went out empty and @alice was never notified.
        val domains = mentionDomains(com.kachat.app.util.KaPostsMarkdown.render(text).text)
        if (domains.isEmpty()) return emptyList()
        val byDomain = mentionCandidates().toMap()
        val found = linkedSetOf<String>()
        for (domain in domains) {
            var pubkey = byDomain[domain]
            if (pubkey == null) {
                val owner = knsService.resolve(domain)
                if (owner != null) pubkey = KaPostsService.kapostPubkeyFromAddress(owner)
            }
            pubkey?.let { found.add(it) }
        }
        return found.toList()
    }

    /** Composer autocomplete: live-resolve the typed @query; the bare domain when it exists. */
    suspend fun resolveMentionQuery(query: String): String? {
        val clean = query.lowercase().removeSuffix(".kas")
        if (clean.length < 2) return null
        return if (knsService.resolve(clean) != null) clean else null
    }

    /** Tapped @mention: resolve the KNS domain and open that user's profile (any KNS holder). */
    fun openMentionProfile(domain: String) {
        viewModelScope.launch {
            val owner = knsService.resolve(domain) ?: return@launch
            openPosterProfile(owner, KaPostsService.kapostPubkeyFromAddress(owner))
        }
    }

    // MARK: - X-style threads (posting)

    /**
     * Unposted work per in-flight/failed thread, keyed by the root's LOCAL id: rootText until
     * the root posts, remaining segments, last landed txid. Kept until every segment lands so
     * Retry RESUMES from the first unposted segment (never duplicates).
     */
    private data class ThreadRemainder(val rootText: String?, val segments: List<String>, val parentTxId: String?)
    private val threadRemainders = mutableMapOf<String, ThreadRemainder>()

    /** Local ids of thread roots posted this session - drives the instant "View thread" link. */
    private val _localThreadRoots = MutableStateFlow<Set<String>>(emptySet())
    val localThreadRoots: StateFlow<Set<String>> = _localThreadRoots.asStateFlow()

    /**
     * First segment = top-level post, each following segment = a reply to the PREVIOUS one.
     * Threads submit sequentially right away (each segment needs the previous txid) - no 5s
     * undo; the optimistic root carries pending/sent/failed for the whole chain.
     */
    fun scheduleThread(segments: List<String>) {
        val first = segments.firstOrNull() ?: return
        if (segments.size == 1) { schedulePost(first); return }
        val myAddress = myAddress() ?: return
        val newPost = KaPostDraft(
            text = first,
            timestamp = System.currentTimeMillis(),
            posterAddress = myAddress,
            posterPubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { null },
            deliveryStatus = KaPostDraft.Delivery.PENDING,
        )
        _localPosts.value = listOf(newPost) + _localPosts.value
        _localThreadRoots.value = _localThreadRoots.value + newPost.id
        // A thread used to submit the instant it was composed - the one compose action with no
        // undo window at all, and the one where a mistake costs the most to fix, since every
        // segment is its own transaction. Same 5s hold as a single post now.
        //
        // threadRemainders is written INSIDE the scheduled block on purpose: it is the resume
        // ledger, and an undone thread must leave nothing behind for a later retry to pick up.
        val key = "post:${newPost.id}"
        _undoToast.value = UndoToast(
            key, newPost.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Posting thread",
            draftText = segments.last(), draftSegments = segments,
        )
        scheduleUndoable(key) {
            clearUndoToast(key)
            threadRemainders[newPost.id] = ThreadRemainder(first, segments.drop(1), null)
            continueThread(newPost.id)
        }
    }

    private fun continueThread(localId: String) {
        if (threadRemainders[localId] == null) return
        mutateEverywhere(localId) { it.copy(deliveryStatus = KaPostDraft.Delivery.PENDING) }
        viewModelScope.launch {
            try {
                var state = threadRemainders[localId] ?: return@launch
                val rootText = state.rootText
                if (rootText != null) {
                    val txId = submitWithUtxoRetry { kaPostsService.submitPost(rootText, mentionedPubkeys(rootText)) }
                    mutateEverywhere(localId) { it.copy(remoteId = txId) }
                    state = state.copy(rootText = null, parentTxId = txId)
                    threadRemainders[localId] = state
                }
                val myPubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { null }
                while (true) {
                    val current = threadRemainders[localId] ?: break
                    val segment = current.segments.firstOrNull() ?: break
                    val parent = current.parentTxId ?: break
                    kotlinx.coroutines.delay(1_500) // let the previous change settle (~1s blocks)
                    val txId = submitWithUtxoRetry { kaPostsService.submitReply(segment, parent, myPubkey, mentionedPubkeys(segment)) }
                    threadRemainders[localId] = current.copy(segments = current.segments.drop(1), parentTxId = txId)
                }
                threadRemainders.remove(localId)
                mutateEverywhere(localId) { it.copy(deliveryStatus = KaPostDraft.Delivery.SENT) }
            } catch (e: Exception) {
                mutateEverywhere(localId) { it.copy(deliveryStatus = KaPostDraft.Delivery.FAILED) }
                Log.w(TAG, "Thread submit failed (resumable)", e)
            }
        }
    }

    /** Rapid sequential sends spend change the node hasn't indexed yet - retry with settle gaps. */
    private suspend fun submitWithUtxoRetry(op: suspend () -> String): String {
        var attempt = 0
        while (true) {
            try { return op() } catch (e: Exception) {
                attempt += 1
                if (attempt > 4) throw e
                Log.w(TAG, "Thread segment retry $attempt", e)
                kotlinx.coroutines.delay(1_500)
            }
        }
    }

    // MARK: - X-style thread reading

    /** remoteId -> "its replies include one by the author" (false is cached: one probe per post). */
    // Seeded with the thread roots found in earlier sessions: a probe answers a question about
    // history that does not change (see KaPostsThreadProbeStore).
    private val _threadRootFlags = MutableStateFlow<Map<String, Boolean>>(
        threadProbeStore.persistedRoots().associateWith { true },
    )
    val threadRootFlags: StateFlow<Map<String, Boolean>> = _threadRootFlags.asStateFlow()

    /** root LOCAL id -> the author's continuation segments, in order. */
    private val _threadChains = MutableStateFlow<Map<String, List<KaPostDraft>>>(emptyMap())
    val threadChains: StateFlow<Map<String, List<KaPostDraft>>> = _threadChains.asStateFlow()

    fun isThreadRoot(post: KaPostDraft): Boolean =
        post.id in _localThreadRoots.value ||
            (post.remoteId != null && _threadRootFlags.value[post.remoteId] == true)

    /**
     * How many probes may be on the wire at once. One flick through a fresh feed reveals dozens
     * of rows, and each used to fire its request immediately (iOS maxInFlight = 4).
     */
    private val threadProbeLimit = kotlinx.coroutines.sync.Semaphore(4)

    /**
     * Cheap once-per-post probe: first reply page, any self-authored reply = thread root.
     *
     * Claims live in [threadProbeStore], persisted across launches, so a post is never probed
     * twice - and the claim is a PLAIN store, not compose state: the flags map only ticks when a
     * post actually IS a thread root, so composing each commented row does not recompose the tab.
     */
    fun probeThreadRoot(post: KaPostDraft) {
        val remoteId = post.remoteId ?: return
        if (_threadRootFlags.value.containsKey(remoteId)) return
        if (commentCount(post) <= 0) return
        if (!threadProbeStore.claim(remoteId)) return
        viewModelScope.launch {
            val page = try {
                threadProbeLimit.withPermit { kaPostsService.fetchRepliesPage(remoteId, 10, null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never got to ask: give the claim back so the next appearance asks the question.
                threadProbeStore.unclaim(remoteId)
                throw e
            } catch (_: Exception) {
                return@launch
            }
            val isThread = page.items.any { KaPostsService.kaspaAddressFromPubkey(it.userPublicKey) == post.posterAddress }
            if (isThread) {
                _threadRootFlags.value = _threadRootFlags.value + (remoteId to true)
                threadProbeStore.markRoot(remoteId)
            }
        }
    }

    /**
     * Walks the author's self-reply chain from an opened root (root <- seg2 <- seg3 ... by the
     * same author), fetching each link. Self-sufficient (fetches the first page itself), so it
     * doesn't race loadReplies. Capped defensively.
     */
    /** Ancestor chains from get-thread, keyed by the post's txid and held root first. */
    private val _fetchedAncestors = MutableStateFlow<Map<String, List<KaPostDraft>>>(emptyMap())
    val fetchedAncestors: StateFlow<Map<String, List<KaPostDraft>>> = _fetchedAncestors.asStateFlow()

    /** One post from the indexer by txid. */
    suspend fun indexerPost(txId: String): KaPostDraft? =
        try { kaPostsService.fetchPost(txId)?.let { mapRemotePost(it) } } catch (_: Exception) { null }

    /**
     * The real chain above a post, fetched once per post.
     *
     * The context used to be the navigation stack - only the levels you had tapped through - so a
     * reply opened from a profile, a link or a notification had nothing above it and no way back
     * to the post it answered.
     */
    fun loadAncestors(post: KaPostDraft) {
        val remoteId = post.remoteId ?: return
        if (post.parentRemoteId.isNullOrEmpty()) return
        if (_fetchedAncestors.value.containsKey(remoteId)) return
        // Claimed before the fetch: the overlay recomposes while this is in flight, and each pass
        // would otherwise start its own request for the same chain.
        _fetchedAncestors.value = _fetchedAncestors.value + (remoteId to emptyList())
        viewModelScope.launch {
            try {
                val mapped = kaPostsService.fetchThread(remoteId).mapNotNull { mapRemotePost(it) }
                if (mapped.isNotEmpty()) {
                    _fetchedAncestors.value = _fetchedAncestors.value + (remoteId to mapped)
                }
            } catch (e: Exception) {
                // Unclaimed, so reopening the post retries rather than showing no context forever.
                _fetchedAncestors.value = _fetchedAncestors.value - remoteId
                Log.w(TAG, "Ancestor chain fetch failed", e)
            }
        }
    }

    fun loadSelfThreadChain(post: KaPostDraft) {
        val rootRemote = post.remoteId ?: return
        viewModelScope.launch {
            val chain = mutableListOf<KaPostDraft>()
            var currentRemote = rootRemote
            var hops = 0
            while (hops < 25) {
                val page = try { kaPostsService.fetchRepliesPage(currentRemote, 25, null) } catch (_: Exception) { break }
                val replies = page.items.mapNotNull { mapRemotePost(it) }
                // An UNBRANCHED continuation is the conversation, whoever wrote it: a two-person
                // back-and-forth is one thread to read, and following only the root author's own
                // replies left every other message behind a tap - one tap down per message, and as
                // many Backs to leave. With SEVERAL replies there is a real branch, and picking one
                // would hide the others, so that keeps the old rule and the rest stay in the list.
                val next = if (replies.size == 1) {
                    replies.first()
                } else {
                    replies.filter { it.posterAddress == post.posterAddress }.minByOrNull { it.timestamp }
                } ?: break
                chain.add(next)
                hops += 1
                currentRemote = next.remoteId ?: break
            }
            _threadChains.value = _threadChains.value + (post.id to chain)
            if (chain.isNotEmpty()) _threadRootFlags.value = _threadRootFlags.value + (rootRemote to true)
        }
    }

    /** Re-submits a failed post or reply (replies resolve their parent for the payload). */
    fun retryPost(post: KaPostDraft) {
        // A failed THREAD resumes its remaining chain instead of re-posting just the root.
        if (threadRemainders.containsKey(post.id)) {
            continueThread(post.id)
            return
        }
        mutateEverywhere(post.id) { it.copy(deliveryStatus = KaPostDraft.Delivery.PENDING) }
        viewModelScope.launch {
            try {
                val parent = findParent(post.id)
                val txId = if (parent != null) {
                    val parentRemoteId = parent.remoteId ?: error("Parent post is not on-chain yet")
                    kaPostsService.submitReply(post.text, parentRemoteId, parent.posterPubkey, mentionedPubkeys(post.text))
                } else {
                    kaPostsService.submitPost(post.text, mentionedPubkeys(post.text))
                }
                mutateEverywhere(post.id) { it.copy(remoteId = txId, deliveryStatus = KaPostDraft.Delivery.SENT) }
            } catch (e: Exception) {
                mutateEverywhere(post.id) { it.copy(deliveryStatus = KaPostDraft.Delivery.FAILED) }
                Log.w(TAG, "Retry failed", e)
            }
        }
    }

    // MARK: - Replies (submit directly with pending state - no undo window, matching iOS)

    /** Page one of a post's replies. Re-entrant: the thread overlay calls it whenever it opens.
     *  [force] reloads page one even when pages are already held (jumping to an ancestor, a
     *  manual retry after a failed first page). */
    fun loadReplies(post: KaPostDraft, force: Boolean = false) {
        val remoteId = post.remoteId ?: return
        val key = pageThread(remoteId)
        // Already loaded and still holding its pages - don't wipe them (and the reader's place in
        // them) just because the overlay recomposed.
        if (!force && surfaceLoaded(key) && !pagingState(key).isLoadingMore) {
            if (findPost(post.id)?.comments?.isNotEmpty() == true) return
        }
        val generation = resetSurface(key)
        updatePaging(key) { it.copy(isLoadingMore = true) }
        loadMoreJobs[key] = viewModelScope.launch {
            val result = accumulate(
                startCursor = null,
                target = TARGET_NEW_ROWS,
                seenIds = emptySet(),
                idOf = KPost::id,
                map = { mapRemotePost(it) },
                isVisible = { !isHidden(it.posterAddress) },
                fetch = { before -> kaPostsService.fetchRepliesPage(remoteId, THREAD_REPLIES_PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            if (result.error == null || result.items.isNotEmpty()) {
                mutateEverywhere(post.id) { target ->
                    // This session's replies stay on top, newest first, until the indexer
                    // returns them - then the server's copy takes over. A reply whose
                    // transaction is already sent has a txid and would otherwise look like an
                    // ordinary server comment, and vanish for the seconds indexing takes.
                    val indexed = result.items.mapNotNull { it.remoteId }.toSet()
                    val mine = target.comments.filter { c ->
                        c.id in sessionReplyIds && (c.remoteId == null || c.remoteId !in indexed)
                    }
                    val otherLocal = target.comments.filter { c ->
                        c.id !in sessionReplyIds && (c.remoteId == null || c.remoteId !in indexed)
                    }
                    target.copy(comments = mine + result.items + otherLocal)
                }
            }
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false,
                    error = result.error, stalled = result.stalled,
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    /** Page one of a post's replies has been asked for at least once (loading, failed or done). */
    fun repliesRequested(post: KaPostDraft): Boolean =
        post.remoteId?.let { surfaceLoaded(pageThread(it)) } ?: false

    /**
     * Everything a thread level needs, from scratch: page one of its replies and the author's
     * continuation. What jumping to an ancestor that was never navigated to runs (iOS
     * jumpToAncestor), since nothing was loaded for that level on the way down.
     */
    fun reloadThread(post: KaPostDraft) {
        loadReplies(post, force = true)
        loadSelfThreadChain(post)
    }

    /** Endless scroll for a thread's replies (and for a nested comment's "Show more replies"). */
    fun loadMoreReplies(post: KaPostDraft, manual: Boolean = false) {
        val remoteId = post.remoteId ?: return
        val key = pageThread(remoteId)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        if (state.stalled && !manual) return
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null, stalled = false) }
        loadMoreJobs[key] = viewModelScope.launch {
            val existing = findPost(post.id)?.comments.orEmpty()
            val result = accumulate(
                startCursor = state.cursor,
                target = TARGET_NEW_ROWS,
                seenIds = existing.mapNotNull { it.remoteId }.toSet(),
                idOf = KPost::id,
                map = { mapRemotePost(it) },
                isVisible = { !isHidden(it.posterAddress) },
                fetch = { before -> kaPostsService.fetchRepliesPage(remoteId, THREAD_REPLIES_PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            if (result.items.isNotEmpty()) {
                mutateEverywhere(post.id) { target ->
                    val known = target.comments.mapNotNull { it.remoteId }.toSet()
                    target.copy(comments = target.comments + result.items.filter { it.remoteId !in known })
                }
            }
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
                    stalled = result.stalled,
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    fun submitReply(parent: KaPostDraft, text: String) {
        val myAddress = myAddress() ?: return
        // The parent is still a local session post with no txid: there is nothing on chain to
        // reply to yet, so the comment is appended as sent - no toast, no submit (iOS).
        if (parent.remoteId == null) {
            val comment = KaPostDraft(
                text = text,
                timestamp = System.currentTimeMillis(),
                posterAddress = myAddress,
                posterPubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { null },
                deliveryStatus = KaPostDraft.Delivery.SENT,
            )
            sessionReplyIds.add(comment.id)
            // Newest first, the order the indexer returns replies in - so a new comment appears
            // right under the post instead of at the far end of a long thread.
            mutateEverywhere(parent.id) { it.copy(comments = listOf(comment) + it.comments) }
            return
        }
        val comment = KaPostDraft(
            text = text,
            timestamp = System.currentTimeMillis(),
            posterAddress = myAddress,
            posterPubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { null },
            deliveryStatus = KaPostDraft.Delivery.PENDING,
        )
        sessionReplyIds.add(comment.id)
        mutateEverywhere(parent.id) { it.copy(comments = listOf(comment) + it.comments) }
        // Same 5s undo TOAST as every other interaction: the optimistic comment shows
        // immediately, the on-chain submit fires when the countdown ends, and Undo removes
        // the comment before anything hits the network.
        val key = "comment:${comment.id}"
        _undoToast.value = UndoToast(
            key, comment.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Posting comment",
            draftText = text, commentParentId = parent.id,
        )
        scheduleUndoable(key) {
            clearUndoToast(key)
            try {
                val parentRemoteId = parent.remoteId ?: error("Post is not on-chain yet")
                // @mentions work in comments exactly like in posts: resolved client-side to pubkeys.
                val txId = kaPostsService.submitReply(text, parentRemoteId, parent.posterPubkey, mentionedPubkeys(text))
                mutateEverywhere(comment.id) { it.copy(remoteId = txId, deliveryStatus = KaPostDraft.Delivery.SENT) }
            } catch (e: Exception) {
                mutateEverywhere(comment.id) { it.copy(deliveryStatus = KaPostDraft.Delivery.FAILED) }
                Log.w(TAG, "Reply submit failed", e)
            }
        }
    }

    // MARK: - Votes (5s in-icon countdown, then on-chain; un-like submits the fork's unvote)

    fun toggleLike(post: KaPostDraft) {
        if (post.likedByMe && post.remoteId == null) {
            performLike(post)   // local-only unlike, instant
            return
        }
        // Every interaction raises the always-visible undo TOAST (the in-icon countdown
        // alone can scroll out of view).
        val key = "like:${post.id}"
        _undoToast.value = UndoToast(key, post.id, System.currentTimeMillis() + UNDO_DELAY_MS, if (post.likedByMe) "Removing like" else "Liking")
        scheduleUndoable(key) {
            clearUndoToast(key)
            performLike(post)
        }
    }

    private fun performLike(post: KaPostDraft) {
        val remoteId = post.remoteId
        val author = post.posterPubkey
        if (remoteId != null && author != null) {
            viewModelScope.launch {
                try {
                    val txId = if (!post.likedByMe) {
                        kaPostsService.submitVote(remoteId, upvote = true, authorPubkey = author)
                    } else {
                        kaPostsService.submitUnvote(remoteId, authorPubkey = author)
                    }
                    showActionToast(if (!post.likedByMe) "Like posted to the network" else "Like removed on the network", txId)
                } catch (e: Exception) {
                    Log.w(TAG, "Vote submit failed", e)
                }
            }
        }
        mutateEverywhere(post.id) { target ->
            if (target.likedByMe) {
                target.copy(likedByMe = false, likes = target.likes - 1)
            } else {
                target.copy(
                    likedByMe = true,
                    likes = target.likes + 1,
                    dislikedByMe = false,
                    dislikes = if (target.dislikedByMe) target.dislikes - 1 else target.dislikes,
                )
            }
        }
    }

    fun toggleDislike(post: KaPostDraft) {
        if (post.dislikedByMe && post.remoteId == null) {
            performDislike(post)
            return
        }
        val key = "dislike:${post.id}"
        _undoToast.value = UndoToast(key, post.id, System.currentTimeMillis() + UNDO_DELAY_MS, if (post.dislikedByMe) "Removing dislike" else "Disliking")
        scheduleUndoable(key) {
            clearUndoToast(key)
            performDislike(post)
        }
    }

    private fun performDislike(post: KaPostDraft) {
        val remoteId = post.remoteId
        val author = post.posterPubkey
        if (remoteId != null && author != null) {
            viewModelScope.launch {
                try {
                    val txId = if (!post.dislikedByMe) {
                        kaPostsService.submitVote(remoteId, upvote = false, authorPubkey = author)
                    } else {
                        kaPostsService.submitUnvote(remoteId, authorPubkey = author)
                    }
                    showActionToast(if (!post.dislikedByMe) "Dislike posted to the network" else "Dislike removed on the network", txId)
                } catch (e: Exception) {
                    Log.w(TAG, "Vote submit failed", e)
                }
            }
        }
        mutateEverywhere(post.id) { target ->
            if (target.dislikedByMe) {
                target.copy(dislikedByMe = false, dislikes = target.dislikes - 1)
            } else {
                target.copy(
                    dislikedByMe = true,
                    dislikes = target.dislikes + 1,
                    likedByMe = false,
                    likes = if (target.likedByMe) target.likes - 1 else target.likes,
                )
            }
        }
    }

    // MARK: - Reposts/quotes (K's repost mechanism IS the quote action)

    /** Plain repost: no optimistic card, 5s undo on the target's repost icon. */
    /** The X-style repost menu's Quote choice, raised from any cell — the main KaPosts screen
     *  collects this and opens its quote composer for the post. */
    private val _quoteRequest = MutableStateFlow<KaPostDraft?>(null)
    val quoteRequest: StateFlow<KaPostDraft?> = _quoteRequest.asStateFlow()

    fun requestQuote(post: KaPostDraft) { _quoteRequest.value = post }

    fun consumeQuoteRequest() { _quoteRequest.value = null }

    fun scheduleRepost(target: KaPostDraft) {
        // A post that is not on chain yet has nothing to quote: the flag just flips locally, the
        // way iOS's toggleRepost treats a local session post.
        if (target.remoteId == null) {
            toggleLocalRepost(target)
            return
        }
        val key = "repost:${target.id}"
        _undoToast.value = UndoToast(key, target.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Reposting")
        scheduleUndoable(key) {
            clearUndoToast(key)
            performRepost(target, text = null, localQuoteId = null)
        }
    }

    /** "Undo Repost": the same 5-second hold, then the fork's unquote counter-action. */
    fun scheduleUnrepost(target: KaPostDraft) {
        if (target.remoteId == null) {
            toggleLocalRepost(target)
            return
        }
        val key = "repost:${target.id}"
        _undoToast.value = UndoToast(key, target.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Removing repost")
        scheduleUndoable(key) {
            clearUndoToast(key)
            performUnrepost(target)
        }
    }

    private fun toggleLocalRepost(target: KaPostDraft) {
        mutateEverywhere(target.id) { post ->
            if (post.repostedByMe) post.copy(repostedByMe = false, reposts = (post.reposts - 1).coerceAtLeast(0))
            else post.copy(repostedByMe = true, reposts = post.reposts + 1)
        }
    }

    private suspend fun performUnrepost(target: KaPostDraft) {
        val contentId = target.remoteId ?: return
        mutateEverywhere(target.id) { post ->
            if (post.repostedByMe) post.copy(repostedByMe = false, reposts = (post.reposts - 1).coerceAtLeast(0)) else post
        }
        try {
            val txId = kaPostsService.submitUnquote(contentId)
            showActionToast("Repost removed on the network", txId)
        } catch (e: Exception) {
            Log.w(TAG, "Unquote submit failed", e)
        }
    }

    /** Quote with commentary: optimistic quote card in the feed + undo toast, like posting. */
    fun scheduleQuote(target: KaPostDraft, text: String) {
        val myAddress = myAddress() ?: return
        val quotePost = KaPostDraft(
            text = text,
            timestamp = System.currentTimeMillis(),
            posterAddress = myAddress,
            posterPubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { null },
            deliveryStatus = KaPostDraft.Delivery.PENDING,
            quoted = KaPostDraft.QuotedRef(
                remoteId = target.remoteId,
                text = target.text,
                posterAddress = target.posterAddress,
                timestamp = target.timestamp,
            ),
        )
        _localPosts.value = listOf(quotePost) + _localPosts.value
        val key = "post:${quotePost.id}"
        _undoToast.value = UndoToast(key, quotePost.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Posting quote", draftText = text, quoteTargetId = target.id)
        scheduleUndoable(key) {
            clearUndoToast(key)
            performRepost(target, text, quotePost.id)
        }
    }

    private suspend fun performRepost(target: KaPostDraft, text: String?, localQuoteId: String?) {
        val contentId = target.remoteId ?: return
        val author = target.posterPubkey ?: return
        mutateEverywhere(target.id) { post ->
            if (!post.repostedByMe) post.copy(repostedByMe = true, reposts = post.reposts + 1) else post
        }
        try {
            val txId = kaPostsService.submitQuote(text, contentId, author)
            showActionToast(
                if (!text.isNullOrEmpty()) "Quote posted to the network" else "Repost posted to the network",
                txId,
            )
            if (localQuoteId != null) {
                mutateEverywhere(localQuoteId) { it.copy(remoteId = txId, deliveryStatus = KaPostDraft.Delivery.SENT) }
            }
        } catch (e: Exception) {
            if (localQuoteId != null) {
                mutateEverywhere(localQuoteId) { it.copy(deliveryStatus = KaPostDraft.Delivery.FAILED) }
            }
            Log.w(TAG, "Quote submit failed", e)
        }
    }

    // MARK: - Follows (local set drives UI instantly; on-chain tx mirrors when pubkey known)

    fun toggleFollow(address: String, pubkey: String?) {
        val walletAddress = myAddress() ?: return
        if (address.isEmpty() || address == walletAddress) return
        val willFollow = address !in following.value
        viewModelScope.launch {
            // Scoped to the account that tapped the button, read fresh from its own key.
            val current = settings.kapostsFollowing(walletAddress).first()
            settings.setKapostsFollowing(walletAddress, if (willFollow) current + address else current - address)
            if (pubkey == null) return@launch
            try {
                val txId = kaPostsService.submitFollow(willFollow, pubkey)
                showActionToast(if (willFollow) "Follow posted to the network" else "Unfollow posted to the network", txId)
            } catch (e: Exception) {
                Log.w(TAG, "Follow submit failed", e)
            }
        }
    }

    // MARK: - Moderation + bookmarks

    fun mute(address: String) {
        if (address.isEmpty()) return
        val wallet = walletManager.activeAddressFlow.value ?: return
        viewModelScope.launch { settings.setKapostsMuted(wallet, muted.value + address) }
    }

    fun unmute(address: String) {
        val wallet = walletManager.activeAddressFlow.value ?: return
        viewModelScope.launch { settings.setKapostsMuted(wallet, muted.value - address) }
    }

    fun block(address: String) {
        if (address.isEmpty()) return
        val wallet = walletManager.activeAddressFlow.value ?: return
        viewModelScope.launch {
            // Block supersedes mute - no need to track both.
            settings.setKapostsBlocked(wallet, blocked.value + address)
            settings.setKapostsMuted(wallet, muted.value - address)
        }
    }

    fun unblock(address: String) {
        val wallet = walletManager.activeAddressFlow.value ?: return
        viewModelScope.launch { settings.setKapostsBlocked(wallet, blocked.value - address) }
    }

    fun toggleBookmark(post: KaPostDraft) {
        mutateEverywhere(post.id) { it.copy(bookmarkedByMe = !it.bookmarkedByMe) }
    }

    /** Comment count for a cell: the indexer's count until replies actually load. */
    fun commentCount(post: KaPostDraft): Int {
        val hidden = muted.value + blocked.value
        return maxOf(post.remoteReplyCount, post.comments.count { it.posterAddress !in hidden })
    }

    // MARK: - Explorer link + share

    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.kachat.app.models.KaspaExplorer.default)

    /**
     * The kachat.app link, and nothing else. It used to come with a quoted snippet of the post in
     * front, which made copying hand over a paragraph rather than a link. The link is the whole
     * story anyway: it unfurls a preview of the post in every chat app, opens KaChat when it is
     * installed, and shows the post with download buttons when it is not.
     */
    fun shareText(post: KaPostDraft): String? {
        val remoteId = post.remoteId ?: return null
        return com.kachat.app.ui.screens.KaChatLink.kaPostWebUrl(remoteId)
    }

    // MARK: - Profiles (mine + tapped poster): banner/counts + on-chain Posts|Replies feeds

    private val _myProfilePosts = MutableStateFlow<List<KaPostDraft>>(emptyList())
    val myProfilePosts: StateFlow<List<KaPostDraft>> = _myProfilePosts.asStateFlow()
    private val _myProfileReplies = MutableStateFlow<List<KaPostDraft>>(emptyList())
    val myProfileReplies: StateFlow<List<KaPostDraft>> = _myProfileReplies.asStateFlow()
    private val _myFollowersCount = MutableStateFlow<Int?>(null)
    val myFollowersCount: StateFlow<Int?> = _myFollowersCount.asStateFlow()
    private val _isLoadingMyProfile = MutableStateFlow(false)
    val isLoadingMyProfile: StateFlow<Boolean> = _isLoadingMyProfile.asStateFlow()

    /** One self-unfollow scrub per session at most (indexer lag would otherwise resubmit). */
    private var selfUnfollowScrubbed = false

    /** My session posts that the indexer hasn't returned yet, merged above remote history. */
    fun myCombinedPosts(): List<KaPostDraft> {
        val my = myAddress() ?: return _myProfilePosts.value
        val remoteIds = _myProfilePosts.value.mapNotNull { it.remoteId }.toSet()
        val localOnly = _localPosts.value.filter { post ->
            post.posterAddress == my && (post.remoteId == null || post.remoteId !in remoteIds)
        }
        return (_myProfilePosts.value + localOnly).sortedByDescending { it.timestamp }
    }

    /**
     * Page one of a profile tab. Both profile screens (mine and a tapped poster's) and both tabs
     * (Posts and Replies) route through here, so each of the four is its own paging surface with
     * its own cursor - switching tabs never disturbs the other one's accumulated pages.
     *
     * Posts come from get-posts (which the deployed indexer serves without replies) and replies
     * from get-replies?user=, hence the two different fetchers.
     */
    private suspend fun loadProfileTab(pubkey: String, isMine: Boolean, replies: Boolean) {
        val key = pageProfile(pubkey, isMine, replies)
        val generation = resetSurface(key)
        // Same page-one guard loadFeed and loadReplies carry: without it, the endless-scroll
        // trigger can fire against the stale list still on screen and run a second cursor=null
        // fetch whose append then duplicates page one (LazyColumn key crash) - resetSurface
        // makes surfaceLoaded true before any rows have actually landed.
        updatePaging(key) { it.copy(isLoadingMore = true) }
        val result = accumulate(
            startCursor = null,
            target = TARGET_NEW_ROWS,
            seenIds = emptySet(),
            idOf = KPost::id,
            // The Posts tab is top-level content only; replies live under the Replies tab.
            map = { post -> mapRemotePost(post)?.takeIf { replies || it.parentRemoteId == null } },
            isVisible = { true },
            fetch = { before ->
                if (replies) kaPostsService.fetchUserRepliesPage(pubkey, PAGE_LIMIT, before)
                else kaPostsService.fetchUserPostsPage(pubkey, PAGE_LIMIT, before)
            },
        )
        if (generations[key] != generation) return
        if (result.error == null || result.items.isNotEmpty()) {
            profileFlow(isMine, replies).value = result.items
            if (isMine) myProfileLoadedAt = System.currentTimeMillis()
            else posterProfileLoaded = pubkey to System.currentTimeMillis()
        }
        updatePaging(key) {
            it.copy(cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false, error = result.error)
        }
    }

    private fun profileFlow(isMine: Boolean, replies: Boolean): MutableStateFlow<List<KaPostDraft>> =
        when {
            isMine && replies -> _myProfileReplies
            isMine -> _myProfilePosts
            replies -> _posterProfileReplies
            else -> _posterProfilePosts
        }

    /** The pubkey a profile screen is showing - mine derives from the wallet, theirs is carried. */
    fun profilePubkey(isMine: Boolean): String? =
        if (isMine) try { kaPostsService.requesterPubkey() } catch (_: Exception) { null }
        else _posterProfile.value?.pubkey

    /** Endless scroll for a profile's Posts or Replies tab. */
    fun loadMoreProfile(isMine: Boolean, replies: Boolean, manual: Boolean = false) {
        val pubkey = profilePubkey(isMine) ?: return
        val key = pageProfile(pubkey, isMine, replies)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        if (state.stalled && !manual) return
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null, stalled = false) }
        loadMoreJobs[key] = viewModelScope.launch {
            val flow = profileFlow(isMine, replies)
            val result = accumulate(
                startCursor = state.cursor,
                target = TARGET_NEW_ROWS,
                seenIds = flow.value.mapNotNull { it.remoteId }.toSet(),
                idOf = KPost::id,
                map = { post -> mapRemotePost(post)?.takeIf { replies || it.parentRemoteId == null } },
                isVisible = { true },
                fetch = { before ->
                    if (replies) kaPostsService.fetchUserRepliesPage(pubkey, PAGE_LIMIT, before)
                    else kaPostsService.fetchUserPostsPage(pubkey, PAGE_LIMIT, before)
                },
            )
            if (generations[key] != generation) return@launch
            if (result.items.isNotEmpty()) flow.value = appendUnique(flow.value, result.items)
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
                    stalled = result.stalled,
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    /** Pull-to-refresh on a profile: page one of the tab being looked at (iOS profileFeedPage). */
    suspend fun refreshProfileTab(isMine: Boolean, replies: Boolean) {
        val pubkey = profilePubkey(isMine) ?: return
        loadProfileTab(pubkey, isMine, replies)
    }

    fun loadMyProfile() {
        viewModelScope.launch {
            val pubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { return@launch }
            _isLoadingMyProfile.value = _myProfilePosts.value.isEmpty()
            try {
                val details = try { kaPostsService.fetchUserDetails(pubkey) } catch (_: Exception) { null }
                if (details != null) {
                    // Never count yourself as your own follower - followedUser with both sides
                    // being us means a stale on-chain self-follow from before the rule. Display
                    // without it and submit a one-time unfollow scrub.
                    if (details.followedUser == true) {
                        _myFollowersCount.value = ((details.followersCount ?: 0) - 1).coerceAtLeast(0)
                        if (!selfUnfollowScrubbed) {
                            selfUnfollowScrubbed = true
                            launch {
                                try { kaPostsService.submitFollow(false, pubkey) } catch (_: Exception) {}
                            }
                        }
                    } else {
                        _myFollowersCount.value = details.followersCount
                    }
                }
                // Replies come from get-replies?user= - the indexer's get-posts never returns them.
                val postsDeferred = async { loadProfileTab(pubkey, isMine = true, replies = false) }
                val repliesDeferred = async { loadProfileTab(pubkey, isMine = true, replies = true) }
                postsDeferred.await()
                repliesDeferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "My profile load failed", e)
            } finally {
                _isLoadingMyProfile.value = false
            }
        }
    }

    data class PosterProfile(
        val address: String,
        val pubkey: String?,
        val followersCount: Int? = null,
        val followingCount: Int? = null,
        val isLoading: Boolean = true,
    )

    private val _posterProfile = MutableStateFlow<PosterProfile?>(null)
    val posterProfile: StateFlow<PosterProfile?> = _posterProfile.asStateFlow()
    private val _posterProfilePosts = MutableStateFlow<List<KaPostDraft>>(emptyList())
    val posterProfilePosts: StateFlow<List<KaPostDraft>> = _posterProfilePosts.asStateFlow()
    private val _posterProfileReplies = MutableStateFlow<List<KaPostDraft>>(emptyList())
    val posterProfileReplies: StateFlow<List<KaPostDraft>> = _posterProfileReplies.asStateFlow()

    /**
     * A snapshot of EVERY list a post can live in, re-emitted whenever any of them changes.
     *
     * [findPost]/[findParent] read plain `StateFlow.value`, which Compose cannot observe - a view
     * that resolves a post by id (the thread overlay) would otherwise keep rendering the object it
     * captured when it first opened, so fetched replies, expanded sub-threads and just-submitted
     * replies would never appear. Collecting this in the composable makes those resolutions
     * recompose. Declared after every backing flow above so property init order is satisfied.
     */
    val postTree: StateFlow<List<List<KaPostDraft>>> = combine(
        listOf(
            _localPosts, _globalPosts, _followingPosts, _posterProfilePosts, _posterProfileReplies,
            _myProfilePosts, _myProfileReplies, _chainPosts,
        )
    ) { lists -> lists.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun openPosterProfile(address: String, pubkey: String?) {
        // The two poster lists are shared by every profile that opens in them, so retire the
        // outgoing profile's paging surfaces: an in-flight load-more for the previous account
        // must not append its rows into the account now on screen.
        retirePosterProfileSurfaces()
        _posterProfile.value = PosterProfile(address = address, pubkey = pubkey)
        _posterProfilePosts.value = emptyList()
        _posterProfileReplies.value = emptyList()
        ensureSenderProfileFetched(address)
        if (pubkey == null) {
            _posterProfile.value = _posterProfile.value?.copy(isLoading = false)
            return
        }
        viewModelScope.launch {
            try {
                val details = try { kaPostsService.fetchUserDetails(pubkey) } catch (_: Exception) { null }
                // Replies come from get-replies?user= - the indexer's get-posts never returns them.
                val postsDeferred = async { loadProfileTab(pubkey, isMine = false, replies = false) }
                val repliesDeferred = async { loadProfileTab(pubkey, isMine = false, replies = true) }
                postsDeferred.await()
                repliesDeferred.await()
                _posterProfile.value = _posterProfile.value?.copy(
                    followersCount = details?.followersCount,
                    followingCount = details?.followingCount,
                    isLoading = false,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Poster profile load failed", e)
                _posterProfile.value = _posterProfile.value?.copy(isLoading = false)
            }
        }
    }

    fun closePosterProfile() {
        retirePosterProfileSurfaces()
        _posterProfile.value = null
    }

    private fun retirePosterProfileSurfaces() {
        val previous = _posterProfile.value?.pubkey ?: return
        resetSurface(pageProfile(previous, isMine = false, replies = false))
        resetSurface(pageProfile(previous, isMine = false, replies = true))
        // resetSurface marks a surface as "loaded"; a closed profile has not been.
        generations.remove(pageProfile(previous, isMine = false, replies = false))
        generations.remove(pageProfile(previous, isMine = false, replies = true))
    }

    // MARK: - Notifications (actions on MY content)

    data class NotificationItem(
        val id: String,          // the ACTION's txid
        val actorAddress: String,
        val kind: Kind,
        val snippet: String?,
        val timestampMs: Long,
        /** Post to open in-app on row tap; null for follows. */
        val targetTxId: String?,
    ) {
        enum class Kind { LIKE, DISLIKE, REPLY, QUOTE, REPOST, FOLLOW, MENTION, OTHER }
    }

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()
    private val _isLoadingNotifications = MutableStateFlow(false)
    val isLoadingNotifications: StateFlow<Boolean> = _isLoadingNotifications.asStateFlow()

    /**
     * Wire notification -> row. Null drops it entirely (our own actions, muted/blocked actors),
     * which is the notification stream's own filter-shrinkage - the fetch loop keeps paging until
     * enough rows survive it.
     */
    /**
     * Which KaPosts activity the user wants to hear about, as a snapshot readable without
     * suspending - [mapNotification] runs inside a mapping lambda and cannot await DataStore.
     */
    private data class KaPostsNotifyPrefs(
        val likes: Boolean = true,
        val dislikes: Boolean = true,
        val comments: Boolean = true,
        val reposts: Boolean = true,
        val follows: Boolean = true,
        val mentions: Boolean = true,
    ) {
        /** Mirrors AppSettingsRepository.shouldNotifyKaPostsAction exactly. */
        fun allows(contentType: String?, voteType: String?): Boolean = when (contentType) {
            "vote" -> if (voteType == "downvote") dislikes else likes
            "reply" -> comments
            "quote" -> reposts
            "follow" -> follows
            "mention" -> mentions
            else -> true
        }
    }

    private val notifyPrefs: kotlinx.coroutines.flow.StateFlow<KaPostsNotifyPrefs> =
        combine(
            settings.kaPostsNotifyLikes,
            settings.kaPostsNotifyDislikes,
            settings.kaPostsNotifyComments,
            settings.kaPostsNotifyReposts,
            settings.kaPostsNotifyFollows,
        ) { likes, dislikes, comments, reposts, follows ->
            KaPostsNotifyPrefs(likes, dislikes, comments, reposts, follows)
        }.combine(settings.kaPostsNotifyMentions) { prefs, mentions ->
            prefs.copy(mentions = mentions)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, KaPostsNotifyPrefs())

    private fun mapNotification(n: com.kachat.app.services.KNotification): NotificationItem? {
        val my = myAddress()
        val address = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: return null
        if (address == my || isHidden(address)) return null
        // A kind switched off in Settings does not belong in this list either. The switch reads
        // "do not tell me about this", and a list full of the thing you muted is the switch not
        // working.
        if (!notifyPrefs.value.allows(n.contentType, n.voteType)) return null
        val text = com.kachat.app.util.KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
        val kind: NotificationItem.Kind
        val target: String?
        when (n.contentType) {
            "vote" -> {
                kind = if (n.voteType == "downvote") NotificationItem.Kind.DISLIKE else NotificationItem.Kind.LIKE
                target = n.contentId
            }
            // The row targets the REPLY itself (iOS). Landing on it goes through the same
            // reply rule as a shared link: the parent's thread opens with this reply spliced
            // into the comments and scrolled into view - see [openSharedPost].
            "reply" -> { kind = NotificationItem.Kind.REPLY; target = n.id }
            "quote" -> {
                kind = if (text.isEmpty()) NotificationItem.Kind.REPOST else NotificationItem.Kind.QUOTE
                target = if (text.isEmpty()) n.contentId else n.id
            }
            "follow" -> { kind = NotificationItem.Kind.FOLLOW; target = null }
            // A mention's acting content IS the post/comment mentioning you — fall back to
            // the notification's own txid when contentId is empty, else the row has no target.
            "mention" -> { kind = NotificationItem.Kind.MENTION; target = n.contentId?.takeIf { it.isNotEmpty() } ?: n.id }
            else -> { kind = NotificationItem.Kind.OTHER; target = n.contentId }
        }
        return NotificationItem(
            id = n.id, actorAddress = address, kind = kind,
            snippet = text.ifEmpty { null }, timestampMs = n.timestamp, targetTxId = target,
        )
    }

    /** The Notifications screen is on screen: the poller drops its banners meanwhile (iOS). */
    fun setNotificationsScreenVisible(visible: Boolean) {
        notificationPoller.isNotificationsScreenVisible = visible
    }

    suspend fun loadNotifications() {
        val key = PAGE_NOTIFICATIONS
        val generation = resetSurface(key)
        _isLoadingNotifications.value = true
        try {
            var newestSeen = 0L
            val result = accumulate(
                startCursor = null,
                target = TARGET_NEW_ROWS,
                seenIds = emptySet(),
                idOf = com.kachat.app.services.KNotification::id,
                map = { n ->
                    newestSeen = maxOf(newestSeen, n.timestamp)
                    mapNotification(n)
                },
                isVisible = { true },
                fetch = { before -> kaPostsService.fetchNotificationsPage(PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return
            if (result.error == null || result.items.isNotEmpty()) {
                _notifications.value = result.items
            }
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false,
                    error = result.error, stalled = result.stalled,
                )
            }
            // What was just displayed should not ping later: advance the poller's watermark
            // and take down any KaPosts banners still in the shade (iOS markSeen).
            myAddress()?.let { address -> notificationPoller.markSeen(address, newestSeen) }
        } finally {
            _isLoadingNotifications.value = false
        }
    }

    fun loadMoreNotifications(manual: Boolean = false) {
        val key = PAGE_NOTIFICATIONS
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        if (state.stalled && !manual) return
        if (_isLoadingNotifications.value) return
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null, stalled = false) }
        loadMoreJobs[key] = viewModelScope.launch {
            val result = accumulate(
                startCursor = state.cursor,
                target = TARGET_NEW_ROWS,
                seenIds = _notifications.value.map { it.id }.toSet(),
                idOf = com.kachat.app.services.KNotification::id,
                map = { mapNotification(it) },
                isVisible = { true },
                fetch = { before -> kaPostsService.fetchNotificationsPage(PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            // Same duplicate-drop as appendUnique: notification ids key their LazyColumn rows.
            if (result.items.isNotEmpty()) {
                val ids = _notifications.value.mapTo(HashSet()) { it.id }
                _notifications.value = _notifications.value + result.items.filterNot { it.id in ids }
            }
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
                    stalled = result.stalled,
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    // MARK: - Engagement (who liked/disliked/reposted/quoted a post)

    data class EngagementEntry(val actionTxId: String, val actorAddress: String, val timestampMs: Long)
    data class EngagementLists(
        val likes: List<EngagementEntry> = emptyList(),
        val dislikes: List<EngagementEntry> = emptyList(),
        val reposts: List<EngagementEntry> = emptyList(),
        val quotes: List<EngagementEntry> = emptyList(),
    )

    /** One actor row paired with the bucket it belongs in, so paging can count per-kind. */
    private data class KindedEntry(val kind: String, val entry: EngagementEntry)

    private val _engagementLists = MutableStateFlow<EngagementLists?>(null)
    val engagementLists: StateFlow<EngagementLists?> = _engagementLists.asStateFlow()
    private val _engagementLoaded = MutableStateFlow(false)
    val engagementLoaded: StateFlow<Boolean> = _engagementLoaded.asStateFlow()

    /** The wire `kind` behind each Post Activity tab, in tab order. */
    fun engagementKindFor(tab: Int): String = when (tab) {
        0 -> "upvote"
        1 -> "downvote"
        2 -> "repost"
        else -> "quote"
    }

    private fun appendEngagement(base: EngagementLists, rows: List<KindedEntry>): EngagementLists {
        val likes = base.likes.toMutableList()
        val dislikes = base.dislikes.toMutableList()
        val reposts = base.reposts.toMutableList()
        val quotes = base.quotes.toMutableList()
        for (row in rows) {
            when (row.kind) {
                "upvote" -> likes.add(row.entry)
                "downvote" -> dislikes.add(row.entry)
                "repost" -> reposts.add(row.entry)
                "quote" -> quotes.add(row.entry)
            }
        }
        return EngagementLists(likes, dislikes, reposts, quotes)
    }

    private fun EngagementLists.allTxIds(): Set<String> =
        (likes + dislikes + reposts + quotes).map { it.actionTxId }.toSet()

    private fun mapEngagement(row: com.kachat.app.services.KEngagementEntry): KindedEntry? {
        val address = KaPostsService.kaspaAddressFromPubkey(row.actorPubkey) ?: return null
        if (row.kind !in setOf("upvote", "downvote", "repost", "quote")) return null
        return KindedEntry(row.kind, EngagementEntry(row.actionTxId, address, row.timestamp))
    }

    /**
     * Actor lists from the fork's get-post-engagement (works for ANY post), falling back to the
     * notification-stream derivation for OWN posts on older deployments. The fallback is a single
     * shot with no cursor, so it ends the surface immediately.
     */
    fun loadEngagement(post: KaPostDraft) {
        val postId = post.remoteId ?: run {
            _engagementLists.value = null
            _engagementLoaded.value = true
            return
        }
        val key = pageEngagement(postId)
        val generation = resetSurface(key)
        _engagementLists.value = null
        _engagementLoaded.value = false
        loadMoreJobs[key] = viewModelScope.launch {
            val result = accumulate(
                startCursor = null,
                target = TARGET_NEW_ROWS,
                seenIds = emptySet(),
                idOf = com.kachat.app.services.KEngagementEntry::actionTxId,
                map = { mapEngagement(it) },
                isVisible = { true },
                fetch = { before -> kaPostsService.fetchPostEngagementPage(postId, "all", PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            if (result.error != null && result.items.isEmpty()) {
                _engagementLists.value = engagementFromNotifications(post, postId)
                updatePaging(key) { it.copy(hasMore = false, isLoadingMore = false) }
            } else {
                _engagementLists.value = appendEngagement(EngagementLists(), result.items)
                updatePaging(key) {
                    it.copy(cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false, error = null)
                }
            }
            _engagementLoaded.value = true
            loadMoreJobs.remove(key)
        }
    }

    /** Endless scroll for Post Activity, targeting the tab the reader is actually on. */
    fun loadMoreEngagement(post: KaPostDraft, tab: Int, manual: Boolean = false) {
        val postId = post.remoteId ?: return
        val key = pageEngagement(postId)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        if (state.stalled && !manual) return
        val wantedKind = engagementKindFor(tab)
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null, stalled = false) }
        loadMoreJobs[key] = viewModelScope.launch {
            val base = _engagementLists.value ?: EngagementLists()
            val result = accumulate(
                startCursor = state.cursor,
                target = TARGET_NEW_ROWS,
                seenIds = base.allTxIds(),
                idOf = com.kachat.app.services.KEngagementEntry::actionTxId,
                map = { mapEngagement(it) },
                // The stream carries all four kinds; only rows for the open tab move it forward.
                isVisible = { it.kind == wantedKind },
                fetch = { before -> kaPostsService.fetchPostEngagementPage(postId, "all", PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            if (result.items.isNotEmpty()) {
                _engagementLists.value = appendEngagement(_engagementLists.value ?: EngagementLists(), result.items)
            }
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
                    stalled = result.stalled,
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    private suspend fun engagementFromNotifications(post: KaPostDraft, postId: String): EngagementLists? {
        if (post.posterAddress != myAddress()) return null
        return try {
            val raw = kaPostsService.fetchNotifications(limit = 100)
            val rows = mutableListOf<KindedEntry>()
            for (n in raw) {
                if (n.contentId != postId) continue
                val address = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
                val entry = EngagementEntry(n.id, address, n.timestamp)
                when (n.contentType) {
                    "vote" -> when (n.voteType) {
                        "upvote" -> rows.add(KindedEntry("upvote", entry))
                        "downvote" -> rows.add(KindedEntry("downvote", entry))
                    }
                    "quote" -> {
                        val text = com.kachat.app.util.KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
                        rows.add(KindedEntry(if (text.isEmpty()) "repost" else "quote", entry))
                    }
                }
            }
            appendEngagement(EngagementLists(), rows)
        } catch (e: Exception) {
            Log.w(TAG, "Engagement fallback failed", e)
            null
        }
    }

    // MARK: - Follow lists (Following / Followers with quick toggle)

    data class FollowEntry(val address: String, val pubkey: String?, val timestampMs: Long?)

    /** Null until the open follow list has loaded once. */
    private val _followEntries = MutableStateFlow<List<FollowEntry>?>(null)
    val followEntries: StateFlow<List<FollowEntry>?> = _followEntries.asStateFlow()

    private fun mapFollowUser(user: com.kachat.app.services.KFollowUser): FollowEntry? {
        val address = KaPostsService.kaspaAddressFromPubkey(user.userPublicKey) ?: return null
        if (address == myAddress()) return null   // never list yourself
        return FollowEntry(address, user.userPublicKey, user.timestamp)
    }

    /**
     * Locally-stored follows the indexer hasn't caught up on, for the tail of the OWN following
     * list. Recomputed against the server rows on every page (iOS): they always sit after
     * whatever the server has returned so far, and a row the server then delivers drops out of
     * the tail rather than appearing twice.
     */
    private fun localOnlyFollows(existing: List<FollowEntry>): List<FollowEntry> {
        val my = myAddress()
        val seen = existing.map { it.address }.toSet()
        return following.value.filter { it !in seen && it != my }.sorted()
            .map { FollowEntry(it, null, null) }
    }

    /**
     * Page one of a follow list. Server order (newest first) is kept as delivered, page after
     * page, so appending can never reshuffle what is already on screen (iOS).
     */
    /// targetPubkey null = the signed-in user's own list (loaded via requesterPubkey, with
    /// locally-stored follows merged in); non-null = another profile's list, server rows only.
    fun loadFollowList(followers: Boolean, targetPubkey: String? = null) {
        val key = pageFollowList(followers)
        // Followers and Following share one list holder (only one is ever open), so retire the
        // other kind's surface - its in-flight page must not land in the list now on screen.
        resetSurface(pageFollowList(!followers))
        generations.remove(pageFollowList(!followers))
        val generation = resetSurface(key)
        val isOwnList = targetPubkey == null
        _followEntries.value = null
        loadMoreJobs[key] = viewModelScope.launch {
            val pubkey = targetPubkey ?: try { kaPostsService.requesterPubkey() } catch (e: Exception) {
                Log.w(TAG, "Follow list load failed", e)
                _followEntries.value = emptyList()
                updatePaging(key) { it.copy(hasMore = false) }
                return@launch
            }
            val result = accumulate(
                startCursor = null,
                target = TARGET_NEW_ROWS,
                seenIds = emptySet(),
                idOf = com.kachat.app.services.KFollowUser::userPublicKey,
                map = { mapFollowUser(it) },
                isVisible = { true },
                fetch = { before -> kaPostsService.fetchFollowListPage(pubkey, followers, FOLLOW_PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            val rows = result.items
            _followEntries.value = if (isOwnList && !followers) rows + localOnlyFollows(rows) else rows
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false,
                    error = result.error, stalled = result.stalled,
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    fun loadMoreFollowList(followers: Boolean, targetPubkey: String? = null, manual: Boolean = false) {
        val key = pageFollowList(followers)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        if (state.stalled && !manual) return
        val generation = generations[key] ?: 0
        val isOwnList = targetPubkey == null
        updatePaging(key) { it.copy(isLoadingMore = true, error = null, stalled = false) }
        loadMoreJobs[key] = viewModelScope.launch {
            val existing = _followEntries.value.orEmpty()
            val pubkey = targetPubkey ?: try { kaPostsService.requesterPubkey() } catch (_: Exception) {
                updatePaging(key) { it.copy(isLoadingMore = false, hasMore = false) }
                return@launch
            }
            val result = accumulate(
                startCursor = state.cursor,
                target = TARGET_NEW_ROWS,
                seenIds = existing.mapNotNull { it.pubkey }.toSet(),
                idOf = com.kachat.app.services.KFollowUser::userPublicKey,
                map = { mapFollowUser(it) },
                isVisible = { true },
                fetch = { before -> kaPostsService.fetchFollowListPage(pubkey, followers, FOLLOW_PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            if (result.items.isNotEmpty()) {
                // Drop the local-only tail before re-appending, so the server rows that just
                // arrived always sit above it.
                val serverRows = existing.filter { it.pubkey != null } + result.items
                _followEntries.value =
                    if (isOwnList && !followers) serverRows + localOnlyFollows(serverRows) else serverRows
            }
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
                    stalled = result.stalled,
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    // MARK: - Chat handoff + bookmarks

    /**
     * Posters usually aren't saved contacts yet - creates a minimal one first (same as
     * broadcasts' sender profiles) so the chat screen has something to load.
     */
    fun ensureContactExists(address: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            if (chatRepository.getContact(address) == null) {
                chatRepository.addContact(
                    com.kachat.app.models.ContactEntity(
                        id = address,
                        walletAddress = walletManager.getAddress(),
                        alias = null,
                        knsName = null,
                        publicKeyHex = null,
                    )
                )
            }
            onReady(address)
        }
    }

    /** Everything bookmarked, posts and comments alike, newest first. */
    fun bookmarkedPosts(): List<KaPostDraft> {
        val hidden = muted.value + blocked.value
        fun collect(list: List<KaPostDraft>): List<KaPostDraft> =
            list.flatMap { listOf(it) + collect(it.comments) }
        return allPostLists()
            .flatMap { collect(it.value) }
            .filter { it.bookmarkedByMe && it.posterAddress !in hidden }
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
    }

    // MARK: - Nested threads + shared-post resolution

    /** Loads a comment's own replies into its comments (inline X-style thread expansion). */
    fun expandReplies(comment: KaPostDraft) = loadReplies(comment)

    /**
     * Where a resolved post lands: the thread to open, and - when the post was a reply - the
     * reply to scroll to once that thread is on screen.
     */
    data class ThreadLanding(val post: KaPostDraft, val scrollToRemoteId: String? = null)

    /**
     * Shared-link/notification/search landing: resolve a txid to a loaded post, refreshing the
     * feed and then own content (notification targets are usually YOUR posts, which live outside
     * the feed window). Returns null when unresolvable.
     *
     * A resolved target that is itself a REPLY lands on its PARENT's thread - the post that was
     * replied to on top, the reply spliced into the comments below and scrolled into view -
     * instead of presenting the bare reply as a context-free thread root (iOS openResolvedPost).
     */
    suspend fun openSharedPost(txId: String): ThreadLanding? {
        val post = resolveSharedPost(txId) ?: return null
        return landingFor(post, includeOwnContent = true)
    }

    /**
     * The landing for a post already in hand - a profile row, a search hit. Same reply rule as
     * [openSharedPost]: a reply opens the post it answers (iOS openProfileDetail).
     */
    suspend fun landingFor(post: KaPostDraft, includeOwnContent: Boolean = false): ThreadLanding {
        val parentId = post.parentRemoteId?.takeIf { it.isNotEmpty() && it != post.remoteId }
        if (parentId != null) {
            var parent = findPostByRemoteId(parentId)
            if (parent == null && includeOwnContent) {
                // A reply notification always targets YOUR content: own posts and replies live
                // outside the feed window, so pull them (unless fresh) before asking the indexer
                // for one id.
                if (reloadMyProfileIfStale()) parent = findPostByRemoteId(parentId)
            }
            if (parent == null) parent = indexerPost(parentId)
            if (parent == null) parent = chainPost(parentId)
            if (parent != null) {
                prepareThreadLanding(parent, ensureComment = post)
                return ThreadLanding(parent, scrollToRemoteId = post.remoteId)
            }
        }
        prepareThreadLanding(post, ensureComment = null)
        return ThreadLanding(post)
    }

    /**
     * A landing must show a FRESH thread: if this thread was already opened earlier in the
     * session, its cached reply page predates the very action that brought the user here
     * (loadReplies keeps existing pages by design). Retiring the surface makes the overlay's
     * loadReplies run a real page-one reload. [ensureComment] is spliced into the comments right
     * now rather than waiting for the indexer: get-replies can lag a push by seconds, so the
     * scroll target exists even before the page that contains it has loaded (iOS ensureComment).
     */
    private fun prepareThreadLanding(thread: KaPostDraft, ensureComment: KaPostDraft?) {
        thread.remoteId?.let { remoteId ->
            val key = pageThread(remoteId)
            resetSurface(key)
            generations.remove(key) // resetSurface marks "loaded"; this thread needs a reload
        }
        val ensuredId = ensureComment?.remoteId?.takeIf { it.isNotEmpty() } ?: return
        // The parent read off the chain lives only in _chainPosts, and a comment can only be
        // spliced into a post that some list holds - so make sure the thread post is held.
        if (findPost(thread.id) == null) {
            _chainPosts.value = _chainPosts.value.filterNot { it.remoteId == thread.remoteId } + thread
        }
        mutateEverywhere(thread.id) { target ->
            if (target.comments.any { it.remoteId == ensuredId }) target
            else target.copy(comments = target.comments + ensureComment)
        }
    }

    /**
     * Own posts+replies from page one, unless they were loaded that way within the last
     * [loadGateStaleAfterMs] - findPost has already searched a fresh set, and resetting would
     * discard whatever the profile screen has scrolled in. Returns whether it fetched.
     */
    private suspend fun reloadMyProfileIfStale(): Boolean {
        val pubkey = try { kaPostsService.requesterPubkey() } catch (e: Exception) {
            Log.w(TAG, "Own-content reload skipped: no requester pubkey", e)
            return false
        }
        val hasContent = _myProfilePosts.value.isNotEmpty() || _myProfileReplies.value.isNotEmpty()
        if (hasContent && isLoadFresh(myProfileLoadedAt)) return false
        // Replies come from get-replies?user= - the indexer's get-posts never returns them.
        loadProfileTab(pubkey, isMine = true, replies = false)
        loadProfileTab(pubkey, isMine = true, replies = true)
        return true
    }

    /** Same for a poster's posts+replies: skipped only when what is loaded is THIS poster's and
     *  still fresh - a different poster's pages are no help in finding their post. */
    private suspend fun reloadPosterProfileIfStale(pubkey: String) {
        val loaded = posterProfileLoaded
        val hasContent = _posterProfilePosts.value.isNotEmpty() || _posterProfileReplies.value.isNotEmpty()
        if (loaded != null && loaded.first == pubkey && isLoadFresh(loaded.second) && hasContent) return
        loadProfileTab(pubkey, isMine = false, replies = false)
        loadProfileTab(pubkey, isMine = false, replies = true)
    }

    private suspend fun resolveSharedPost(txId: String): KaPostDraft? {
        findPostByRemoteId(txId)?.let { return it }
        // One request for the exact id, before re-fetching whole feeds and profiles in the hope
        // the post falls inside one of them.
        indexerPost(txId)?.let { return it }
        // Only re-pull page one when there is nothing loaded or it has gone stale: a reset throws
        // away every page the reader has scrolled in, and a fresh window was already searched.
        if (_globalPosts.value.isEmpty() || !isLoadFresh(feedLoadedAt)) {
            loadFeed(FeedTab.FEED)
            findPostByRemoteId(txId)?.let { return it }
        }
        if (reloadMyProfileIfStale()) {
            findPostByRemoteId(txId)?.let { return it }
        }
        // Still unresolved: the txid is usually a notification's ACTING content - someone
        // ELSE's reply/quote/mentioning post, which neither the feed window nor the own-
        // content fetch above ever returns (there is no fetch-post-by-id endpoint). The
        // notification stream knows who wrote it: pull THAT author's posts+replies, then
        // fall back to the parent conversation when the acting content itself still hides.
        try {
            val n = kaPostsService.fetchNotifications(limit = 100).find { it.id == txId }
            if (n != null) {
                reloadPosterProfileIfStale(n.userPublicKey)
                findPostByRemoteId(txId)?.let { return it }
                n.contentId?.takeIf { it.isNotEmpty() }?.let { parentId ->
                    findPostByRemoteId(parentId)?.let { return it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shared-post actor-content fetch failed", e)
        }
        findPostByRemoteId(txId)?.let { return it }
        // Last resort, and the one that always works: read the post off the transaction it was
        // published as. Everything above searches the indexer, which has no single-post lookup,
        // so a post outside the feed window and outside the fetched profiles simply could not be
        // found - that is what "Post not found" always was. The chain has every post that ever
        // existed. See [KaPostChainReader].
        return chainPost(txId)
    }

    /**
     * Builds a post from its own transaction, for the cases the indexer cannot answer.
     *
     * The payload carries the text, the author and (for a reply or a quote) what it points at;
     * the transaction carries the time. Engagement is then filled from get-post-engagement,
     * which works for ANY post id, so a post opened this way shows real like/dislike/repost
     * numbers and your own vote state rather than a row of zeros.
     */
    private suspend fun chainPost(txId: String): KaPostDraft? {
        val record = chainReader.fetch(txId) ?: return null
        val address = KaPostsService.kaspaAddressFromPubkey(record.authorPubkey) ?: return null
        // The quoted post's own text is a second chain read - a quoted card with no text is
        // worse than resolving it properly.
        val quoted = if (record.action == "quote" && record.referencedId != null) {
            chainReader.fetch(record.referencedId)?.let { q ->
                KaPostsService.kaspaAddressFromPubkey(q.authorPubkey)?.let { quotedAddress ->
                    KaPostDraft.QuotedRef(
                        remoteId = record.referencedId,
                        text = q.message,
                        posterAddress = quotedAddress,
                        timestamp = q.blockTimeMillis,
                    )
                }
            }
        } else null
        val engagement = chainEngagement(txId)
        val post = KaPostDraft(
            id = KaPostDraft.stableId(txId),
            text = record.message,
            timestamp = record.blockTimeMillis ?: System.currentTimeMillis(),
            posterAddress = address,
            remoteId = txId,
            posterPubkey = record.authorPubkey,
            likes = engagement?.likes ?: 0,
            dislikes = engagement?.dislikes ?: 0,
            reposts = engagement?.reposts ?: 0,
            likedByMe = engagement?.likedByMe ?: false,
            dislikedByMe = engagement?.dislikedByMe ?: false,
            repostedByMe = engagement?.repostedByMe ?: false,
            quoted = quoted,
            // A quote's referenced id is the post it quotes, NOT a parent - only a reply has one.
            parentRemoteId = if (record.action == "reply") record.referencedId else null,
        )
        // Held so the thread overlay can resolve the id it is about to be handed - see
        // [_chainPosts]. Replacing an existing copy keeps the stable id pointing at one node, so
        // a like made in the thread does not land on a stale twin.
        _chainPosts.value = _chainPosts.value.filterNot { it.remoteId == txId } + post
        return post
    }

    private data class ChainEngagement(
        val likes: Int,
        val dislikes: Int,
        val reposts: Int,
        val likedByMe: Boolean,
        val dislikedByMe: Boolean,
        val repostedByMe: Boolean,
    )

    /** Counts the actor rows get-post-engagement returns, and spots our own among them. */
    private suspend fun chainEngagement(txId: String): ChainEngagement? {
        val entries = try {
            kaPostsService.fetchPostEngagementPage(txId, limit = 100).items
        } catch (e: Exception) {
            Log.w(TAG, "Chain-post engagement fetch failed", e)
            return null
        }
        val me = try { kaPostsService.requesterPubkey().lowercase() } catch (e: Exception) { null }
        // A quote counts as a repost, matching how the feed's own counts are built.
        fun isRepost(kind: String) = kind == "repost" || kind == "quote"
        return ChainEngagement(
            likes = entries.count { it.kind == "upvote" },
            dislikes = entries.count { it.kind == "downvote" },
            reposts = entries.count { isRepost(it.kind) },
            likedByMe = me != null && entries.any { it.kind == "upvote" && it.actorPubkey.lowercase() == me },
            dislikedByMe = me != null && entries.any { it.kind == "downvote" && it.actorPubkey.lowercase() == me },
            repostedByMe = me != null && entries.any { isRepost(it.kind) && it.actorPubkey.lowercase() == me },
        )
    }
}
