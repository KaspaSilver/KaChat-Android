package com.kachat.app.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.KaPostDraft
import com.kachat.app.models.findPostByRemoteIdIn
import com.kachat.app.models.findPostIn
import com.kachat.app.models.mutatePostIn
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.KPost
import com.kachat.app.services.KaPostsService
import com.kachat.app.services.KnsService
import com.kachat.app.services.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val chatRepository: ChatRepository,
    private val settings: AppSettingsRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "KaPostsViewModel"
        const val UNDO_DELAY_MS = 5_000L

        /** Rows requested per HTTP page. Small enough to stay snappy, big enough that the
         *  KaChat-marker filter usually still leaves something behind. */
        private const val PAGE_LIMIT = 25

        /** How many NEW VISIBLE rows one load-more trigger tries to accumulate. */
        private const val TARGET_NEW_ROWS = 18

        /** Hard cap on HTTP requests per trigger, so a feed that is 99% non-KaChat content can
         *  never turn one flick of the thumb into an unbounded crawl of the whole index. */
        private const val MAX_REQUESTS_PER_TRIGGER = 5

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

    /** True once this surface has been loaded at least once in this session. */
    private fun surfaceLoaded(key: String): Boolean = generations.containsKey(key)

    private fun resetSurface(key: String): Int {
        val generation = (generations[key] ?: 0) + 1
        generations[key] = generation
        loadMoreJobs.remove(key)?.cancel()
        _paging.value = _paging.value + (key to PagingState())
        return generation
    }

    /** What one fetch loop produced: new rows (hidden ones included), where to resume, and why it stopped. */
    private data class Accumulation<R>(
        val items: List<R>,
        val cursor: String?,
        val hasMore: Boolean,
        val error: String? = null,
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
        while (requests < MAX_REQUESTS_PER_TRIGGER && visibleCount < target && hasMore) {
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
        return Accumulation(collected, cursor, hasMore, error)
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

    val following: StateFlow<Set<String>> = settings.kapostsFollowing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val muted: StateFlow<Set<String>> = settings.kapostsMuted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val blocked: StateFlow<Set<String>> = settings.kapostsBlocked
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
        FeedTab.POPULAR -> globalVisible.sortedByDescending { it.likes + it.reposts + it.dislikes }
    }

    /** The selected tab's feed - kept for callers that only care about what's on screen. */
    val visibleFeed: StateFlow<List<KaPostDraft>> = combine(
        visiblePosts, visibleFollowingPosts, _selectedFeed,
    ) { global, followingFeed, tab -> feedFor(tab, global, followingFeed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // MARK: - Toasts + undo scheduler

    /** Transient confirmation that an on-chain action landed, with a link to the tx. */
    data class ActionToast(val id: String = UUID.randomUUID().toString(), val message: String, val txId: String)

    private val _actionToast = MutableStateFlow<ActionToast?>(null)
    val actionToast: StateFlow<ActionToast?> = _actionToast.asStateFlow()

    /** 5-second undo window for a just-composed post/quote. */
    data class UndoToast(val key: String, val postId: String, val deadlineMs: Long, val label: String)

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

    private fun showActionToast(message: String, txId: String) {
        val toast = ActionToast(message = message, txId = txId)
        _actionToast.value = toast
        viewModelScope.launch {
            delay(4_000)
            if (_actionToast.value?.id == toast.id) _actionToast.value = null
        }
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

    fun ensureSenderProfileFetched(address: String) {
        if (address.isEmpty() || _senderProfiles.value.containsKey(address)) return
        _senderProfiles.value = _senderProfiles.value + (address to null)
        viewModelScope.launch {
            try {
                val ownedAssets = knsService.getOwnedDomains(address)
                if (ownedAssets.isEmpty()) return@launch
                val ownedNames = ownedAssets.mapNotNull { it.asset }
                val primary = knsService.reverseResolve(address)
                val activeName = KnsService.pickActiveDomain(ownedNames, null, primary)
                _senderKnsNames.value = _senderKnsNames.value + (address to activeName)
                val activeAsset = ownedAssets.firstOrNull { it.asset == activeName }
                val checkOrder = listOfNotNull(activeAsset) + ownedAssets.filterNot { it.asset == activeName }
                for (asset in checkOrder) {
                    val profile = asset.assetId?.let { knsService.getProfile(it) } ?: continue
                    if (_senderProfiles.value[address] == null && profile.avatarUrl != null) {
                        _senderProfiles.value = _senderProfiles.value + (address to profile.avatarUrl)
                    }
                    if (_senderBanners.value[address] == null && profile.bannerUrl != null) {
                        _senderBanners.value = _senderBanners.value + (address to profile.bannerUrl)
                    }
                    if (_senderBios.value[address] == null && !profile.bio.isNullOrBlank()) {
                        _senderBios.value = _senderBios.value + (address to profile.bio)
                    }
                    if (_senderProfiles.value[address] != null && _senderBanners.value[address] != null) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch KNS profile for $address", e)
            }
        }
    }

    /** "alice.kas" reads better as just "alice" - the .kas is implied everywhere in KaPosts. */
    fun strippingKasSuffix(domain: String): String {
        val trimmed = domain.trim()
        return if (trimmed.lowercase().endsWith(".kas")) trimmed.dropLast(4) else trimmed
    }

    /** Contact alias > KNS domain > shortened address. */
    fun posterDisplayName(address: String): String {
        if (address.isEmpty()) return "Unknown"
        contactAliases.value[address]?.takeIf { it.isNotBlank() }?.let { return strippingKasSuffix(it) }
        _senderKnsNames.value[address]?.takeIf { it.isNotBlank() }?.let { return strippingKasSuffix(it) }
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
    fun selectFeed(tab: FeedTab) {
        _selectedFeed.value = tab
        if (!surfaceLoaded(feedKey(tab))) viewModelScope.launch { loadFeed(tab) }
    }

    /**
     * Page one for [tab]: clears the accumulated pages, the cursor and the end-reached flag.
     *
     * The in-flight guard is PER TAB (the surface's own flag), not the shared header spinner -
     * swiping to Following while the global feed is still loading has to be able to load
     * Following, and the two write to different lists anyway.
     */
    suspend fun loadFeed(tab: FeedTab = _selectedFeed.value) {
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
            }
            updatePaging(key) {
                it.copy(cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false, error = null)
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
    fun loadMoreFeed(tab: FeedTab) {
        val key = feedKey(tab)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || _isLoadingFeed.value) return
        if (!surfaceLoaded(key)) return
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null) }
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
            if (result.items.isNotEmpty()) flow.value = flow.value + result.items
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    // A failed load-more keeps the list and stays "has more" so retry can resume.
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
                )
            }
            loadMoreJobs.remove(key)
        }
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
        )
    }

    // MARK: - Post tree mutation (local + remote lists; profile lists arrive in Phase B)

    /** Every list a post can live in, in iOS mutatePost's search order. */
    private fun allPostLists(): List<MutableStateFlow<List<KaPostDraft>>> = listOf(
        _localPosts, _globalPosts, _followingPosts, _posterProfilePosts, _posterProfileReplies,
        _myProfilePosts, _myProfileReplies,
    )

    private fun mutateEverywhere(id: String, transform: (KaPostDraft) -> KaPostDraft) {
        for (flow in allPostLists()) {
            val (updated, hit) = mutatePostIn(flow.value, id, transform)
            if (hit) {
                flow.value = updated
                return
            }
        }
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
        _undoToast.value = UndoToast(key, newPost.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Posting")
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
        _localPosts.value = _localPosts.value.filterNot { it.id == toast.postId }
        _undoToast.value = null
    }

    private suspend fun submitScheduledPost(localId: String, text: String) {
        try {
            val txId = kaPostsService.submitPost(text)
            mutateEverywhere(localId) { it.copy(remoteId = txId, deliveryStatus = KaPostDraft.Delivery.SENT) }
        } catch (e: Exception) {
            mutateEverywhere(localId) { it.copy(deliveryStatus = KaPostDraft.Delivery.FAILED) }
            Log.w(TAG, "Post submit failed", e)
        }
    }

    /** Re-submits a failed post or reply (replies resolve their parent for the payload). */
    fun retryPost(post: KaPostDraft) {
        mutateEverywhere(post.id) { it.copy(deliveryStatus = KaPostDraft.Delivery.PENDING) }
        viewModelScope.launch {
            try {
                val parent = findParent(post.id)
                val txId = if (parent != null) {
                    val parentRemoteId = parent.remoteId ?: error("Parent post is not on-chain yet")
                    kaPostsService.submitReply(post.text, parentRemoteId, parent.posterPubkey)
                } else {
                    kaPostsService.submitPost(post.text)
                }
                mutateEverywhere(post.id) { it.copy(remoteId = txId, deliveryStatus = KaPostDraft.Delivery.SENT) }
            } catch (e: Exception) {
                mutateEverywhere(post.id) { it.copy(deliveryStatus = KaPostDraft.Delivery.FAILED) }
                Log.w(TAG, "Retry failed", e)
            }
        }
    }

    // MARK: - Replies (submit directly with pending state - no undo window, matching iOS)

    /** Page one of a post's replies. Re-entrant: the thread overlay calls it whenever it opens. */
    fun loadReplies(post: KaPostDraft) {
        val remoteId = post.remoteId ?: return
        val key = pageThread(remoteId)
        // Already loaded and still holding its pages - don't wipe them (and the reader's place in
        // them) just because the overlay recomposed.
        if (surfaceLoaded(key) && !pagingState(key).isLoadingMore) {
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
                fetch = { before -> kaPostsService.fetchRepliesPage(remoteId, PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            if (result.error == null || result.items.isNotEmpty()) {
                mutateEverywhere(post.id) { target ->
                    // Keep locally-composed comments that the indexer hasn't caught up to yet.
                    val localOnly = target.comments.filter { c ->
                        c.remoteId == null || result.items.none { it.remoteId == c.remoteId }
                    }
                    target.copy(comments = result.items + localOnly)
                }
            }
            updatePaging(key) {
                it.copy(cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false, error = result.error)
            }
            loadMoreJobs.remove(key)
        }
    }

    /** Endless scroll for a thread's replies (and for a nested comment's "Show more replies"). */
    fun loadMoreReplies(post: KaPostDraft) {
        val remoteId = post.remoteId ?: return
        val key = pageThread(remoteId)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null) }
        loadMoreJobs[key] = viewModelScope.launch {
            val existing = findPost(post.id)?.comments.orEmpty()
            val result = accumulate(
                startCursor = state.cursor,
                target = TARGET_NEW_ROWS,
                seenIds = existing.mapNotNull { it.remoteId }.toSet(),
                idOf = KPost::id,
                map = { mapRemotePost(it) },
                isVisible = { !isHidden(it.posterAddress) },
                fetch = { before -> kaPostsService.fetchRepliesPage(remoteId, PAGE_LIMIT, before) },
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
                )
            }
            loadMoreJobs.remove(key)
        }
    }

    fun submitReply(parent: KaPostDraft, text: String) {
        val myAddress = myAddress() ?: return
        val comment = KaPostDraft(
            text = text,
            timestamp = System.currentTimeMillis(),
            posterAddress = myAddress,
            posterPubkey = try { kaPostsService.requesterPubkey() } catch (_: Exception) { null },
            deliveryStatus = KaPostDraft.Delivery.PENDING,
        )
        mutateEverywhere(parent.id) { it.copy(comments = it.comments + comment) }
        viewModelScope.launch {
            try {
                val parentRemoteId = parent.remoteId ?: error("Post is not on-chain yet")
                val txId = kaPostsService.submitReply(text, parentRemoteId, parent.posterPubkey)
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
        scheduleUndoable("like:${post.id}") { performLike(post) }
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
        scheduleUndoable("dislike:${post.id}") { performDislike(post) }
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
    fun scheduleRepost(target: KaPostDraft) {
        scheduleUndoable("repost:${target.id}") { performRepost(target, text = null, localQuoteId = null) }
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
        _undoToast.value = UndoToast(key, quotePost.id, System.currentTimeMillis() + UNDO_DELAY_MS, "Posting quote")
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
        if (address.isEmpty() || address == myAddress()) return
        val willFollow = address !in following.value
        viewModelScope.launch {
            val current = following.value
            settings.setKapostsFollowing(if (willFollow) current + address else current - address)
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
        viewModelScope.launch { settings.setKapostsMuted(muted.value + address) }
    }

    fun unmute(address: String) {
        viewModelScope.launch { settings.setKapostsMuted(muted.value - address) }
    }

    fun block(address: String) {
        if (address.isEmpty()) return
        viewModelScope.launch {
            // Block supersedes mute - no need to track both.
            settings.setKapostsBlocked(blocked.value + address)
            settings.setKapostsMuted(muted.value - address)
        }
    }

    fun unblock(address: String) {
        viewModelScope.launch { settings.setKapostsBlocked(blocked.value - address) }
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

    /** Short snippet + the kachat:// deep link - opens the post straight in the app. */
    fun shareText(post: KaPostDraft): String? {
        val remoteId = post.remoteId ?: return null
        val snippet = post.text.take(60).trim()
        val ellipsis = if (post.text.length > 60) "..." else ""
        return "\"$snippet$ellipsis\"\n\nOpen in KaChat: kachat://kapost/$remoteId"
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
    fun loadMoreProfile(isMine: Boolean, replies: Boolean) {
        val pubkey = profilePubkey(isMine) ?: return
        val key = pageProfile(pubkey, isMine, replies)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null) }
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
            if (result.items.isNotEmpty()) flow.value = flow.value + result.items
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
                )
            }
            loadMoreJobs.remove(key)
        }
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
            _myProfilePosts, _myProfileReplies,
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
        enum class Kind { LIKE, DISLIKE, REPLY, QUOTE, REPOST, FOLLOW, OTHER }
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
    private fun mapNotification(n: com.kachat.app.services.KNotification): NotificationItem? {
        val my = myAddress()
        val address = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: return null
        if (address == my || isHidden(address)) return null
        val text = com.kachat.app.util.KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
        val kind: NotificationItem.Kind
        val target: String?
        when (n.contentType) {
            "vote" -> {
                kind = if (n.voteType == "downvote") NotificationItem.Kind.DISLIKE else NotificationItem.Kind.LIKE
                target = n.contentId
            }
            "reply" -> { kind = NotificationItem.Kind.REPLY; target = n.id }
            "quote" -> {
                kind = if (text.isEmpty()) NotificationItem.Kind.REPOST else NotificationItem.Kind.QUOTE
                target = if (text.isEmpty()) n.contentId else n.id
            }
            "follow" -> { kind = NotificationItem.Kind.FOLLOW; target = null }
            else -> { kind = NotificationItem.Kind.OTHER; target = n.contentId }
        }
        return NotificationItem(
            id = n.id, actorAddress = address, kind = kind,
            snippet = text.ifEmpty { null }, timestampMs = n.timestamp, targetTxId = target,
        )
    }

    fun loadNotifications() {
        val key = PAGE_NOTIFICATIONS
        val generation = resetSurface(key)
        viewModelScope.launch {
            _isLoadingNotifications.value = true
            try {
                val result = accumulate(
                    startCursor = null,
                    target = TARGET_NEW_ROWS,
                    seenIds = emptySet(),
                    idOf = com.kachat.app.services.KNotification::id,
                    map = { mapNotification(it) },
                    isVisible = { true },
                    fetch = { before -> kaPostsService.fetchNotificationsPage(PAGE_LIMIT, before) },
                )
                if (generations[key] != generation) return@launch
                if (result.error == null || result.items.isNotEmpty()) {
                    _notifications.value = result.items
                }
                updatePaging(key) {
                    it.copy(cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false, error = result.error)
                }
            } finally {
                _isLoadingNotifications.value = false
            }
        }
    }

    fun loadMoreNotifications() {
        val key = PAGE_NOTIFICATIONS
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        if (_isLoadingNotifications.value) return
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null) }
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
            if (result.items.isNotEmpty()) _notifications.value = _notifications.value + result.items
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
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
    fun loadMoreEngagement(post: KaPostDraft, tab: Int) {
        val postId = post.remoteId ?: return
        val key = pageEngagement(postId)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        val wantedKind = engagementKindFor(tab)
        val generation = generations[key] ?: 0
        updatePaging(key) { it.copy(isLoadingMore = true, error = null) }
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
     * Locally-stored follows the indexer hasn't caught up on. They are appended ONCE, when the
     * server list has actually run out - appending them per page would duplicate them.
     */
    private fun localOnlyFollows(existing: List<FollowEntry>): List<FollowEntry> {
        val my = myAddress()
        val seen = existing.map { it.address }.toSet()
        return following.value.filter { it !in seen && it != my }.sorted()
            .map { FollowEntry(it, null, null) }
    }

    /**
     * Page one of a follow list. Server order (newest first) is preserved across pages; only the
     * first page is sorted, so appending can never reshuffle what is already on screen.
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
                fetch = { before -> kaPostsService.fetchFollowListPage(pubkey, followers, PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            var rows = result.items.sortedByDescending { it.timestampMs ?: 0L }
            if (isOwnList && !followers && !result.hasMore) rows = rows + localOnlyFollows(rows)
            _followEntries.value = rows
            updatePaging(key) {
                it.copy(cursor = result.cursor, hasMore = result.hasMore, isLoadingMore = false, error = result.error)
            }
            loadMoreJobs.remove(key)
        }
    }

    fun loadMoreFollowList(followers: Boolean, targetPubkey: String? = null) {
        val key = pageFollowList(followers)
        val state = pagingState(key)
        if (state.isLoadingMore || !state.hasMore || !surfaceLoaded(key)) return
        val generation = generations[key] ?: 0
        val isOwnList = targetPubkey == null
        updatePaging(key) { it.copy(isLoadingMore = true, error = null) }
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
                fetch = { before -> kaPostsService.fetchFollowListPage(pubkey, followers, PAGE_LIMIT, before) },
            )
            if (generations[key] != generation) return@launch
            if (result.items.isNotEmpty() || (isOwnList && !followers && !result.hasMore)) {
                // Drop any local-only placeholders before re-appending, so the server rows that
                // just arrived always sit above them.
                val serverRows = existing.filter { it.pubkey != null } + result.items
                _followEntries.value =
                    if (isOwnList && !followers && !result.hasMore) serverRows + localOnlyFollows(serverRows) else serverRows
            }
            updatePaging(key) {
                it.copy(
                    cursor = result.cursor,
                    hasMore = if (result.error != null) it.hasMore else result.hasMore,
                    isLoadingMore = false,
                    error = result.error,
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
     * Shared-link/notification landing: resolve a txid to a loaded post, refreshing the feed
     * and then own content (notification targets are usually YOUR posts, which live outside
     * the feed window). Returns null when unresolvable (other people's older posts - a true
     * get-post endpoint on the fork would make this exact).
     */
    suspend fun openSharedPost(txId: String): KaPostDraft? {
        findPostByRemoteId(txId)?.let { return it }
        loadFeed()
        findPostByRemoteId(txId)?.let { return it }
        try {
            val pubkey = kaPostsService.requesterPubkey()
            // Replies come from get-replies?user= - the indexer's get-posts never returns them.
            loadProfileTab(pubkey, isMine = true, replies = false)
            loadProfileTab(pubkey, isMine = true, replies = true)
        } catch (e: Exception) {
            Log.w(TAG, "Shared-post own-content fetch failed", e)
        }
        return findPostByRemoteId(txId)
    }
}
