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
    }

    enum class FeedTab { FOLLOWING, FEED, POPULAR }

    // MARK: - Feed state

    private val _selectedFeed = MutableStateFlow(FeedTab.FEED)
    val selectedFeed: StateFlow<FeedTab> = _selectedFeed.asStateFlow()

    /** Local session posts (composer output) - overlaid on top of remote posts until indexed. */
    private val _localPosts = MutableStateFlow<List<KaPostDraft>>(emptyList())
    val localPosts: StateFlow<List<KaPostDraft>> = _localPosts.asStateFlow()

    /** Posts fetched from the K indexer (already KaChat-marker-filtered by the service). */
    private val _remotePosts = MutableStateFlow<List<KaPostDraft>>(emptyList())

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

    /**
     * The visible feed for the selected tab: session posts first, then remote posts deduped by
     * remote id; muted/blocked authors hidden everywhere; Following filters to followed
     * addresses; Popular sorts by total engagement.
     */
    val visibleFeed: StateFlow<List<KaPostDraft>> = combine(
        _localPosts, _remotePosts, _selectedFeed, following,
        combine(muted, blocked) { m, b -> m + b },
    ) { local, remote, tab, followingSet, hiddenSet ->
        val combined = local + remote.filter { r ->
            local.none { it.remoteId != null && it.remoteId == r.remoteId }
        }
        val visible = combined.filter { it.posterAddress !in hiddenSet }
        when (tab) {
            FeedTab.FOLLOWING -> visible.filter { it.posterAddress in followingSet }
            FeedTab.FEED -> visible
            FeedTab.POPULAR -> visible.sortedByDescending { it.likes + it.reposts + it.dislikes }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun selectFeed(tab: FeedTab) {
        _selectedFeed.value = tab
        viewModelScope.launch { loadFeed() }
    }

    suspend fun loadFeed() {
        if (_isLoadingFeed.value) return
        _isLoadingFeed.value = true
        _feedError.value = null
        try {
            val result = when (_selectedFeed.value) {
                FeedTab.FOLLOWING -> kaPostsService.fetchFollowingFeed()
                FeedTab.FEED, FeedTab.POPULAR -> kaPostsService.fetchGlobalFeed()
            }
            _remotePosts.value = result.mapNotNull { mapRemotePost(it) }
        } catch (e: Exception) {
            _feedError.value = e.message ?: "Could not load the feed"
            Log.w(TAG, "Feed fetch failed", e)
        } finally {
            _isLoadingFeed.value = false
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
        _localPosts, _remotePosts, _posterProfilePosts, _posterProfileReplies,
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

    fun loadReplies(post: KaPostDraft) {
        val remoteId = post.remoteId ?: return
        viewModelScope.launch {
            try {
                val replies = kaPostsService.fetchReplies(remoteId).mapNotNull { mapRemotePost(it) }
                mutateEverywhere(post.id) { target ->
                    // Keep locally-composed comments that the indexer hasn't caught up to yet.
                    val localOnly = target.comments.filter { c ->
                        c.remoteId == null || replies.none { it.remoteId == c.remoteId }
                    }
                    target.copy(comments = replies + localOnly)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Replies fetch failed", e)
            }
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
                val postsDeferred = async { kaPostsService.fetchUserPosts(pubkey) }
                val repliesDeferred = async {
                    try { kaPostsService.fetchUserReplies(pubkey) } catch (e: Exception) {
                        Log.w(TAG, "My profile replies fetch failed", e); emptyList()
                    }
                }
                _myProfilePosts.value = postsDeferred.await().mapNotNull { mapRemotePost(it) }
                    .filter { it.parentRemoteId == null }
                _myProfileReplies.value = repliesDeferred.await().mapNotNull { mapRemotePost(it) }
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

    fun openPosterProfile(address: String, pubkey: String?) {
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
                val postsDeferred = async { kaPostsService.fetchUserPosts(pubkey) }
                val repliesDeferred = async {
                    try { kaPostsService.fetchUserReplies(pubkey) } catch (e: Exception) {
                        Log.w(TAG, "Poster profile replies fetch failed", e); emptyList()
                    }
                }
                _posterProfilePosts.value = postsDeferred.await().mapNotNull { mapRemotePost(it) }
                    .filter { it.parentRemoteId == null }
                _posterProfileReplies.value = repliesDeferred.await().mapNotNull { mapRemotePost(it) }
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
        _posterProfile.value = null
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

    fun loadNotifications() {
        viewModelScope.launch {
            _isLoadingNotifications.value = true
            try {
                val raw = kaPostsService.fetchNotifications(limit = 100)
                val my = myAddress()
                val hidden = muted.value + blocked.value
                _notifications.value = raw.mapNotNull { n ->
                    val address = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: return@mapNotNull null
                    if (address == my || address in hidden) return@mapNotNull null
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
                    NotificationItem(
                        id = n.id, actorAddress = address, kind = kind,
                        snippet = text.ifEmpty { null }, timestampMs = n.timestamp, targetTxId = target,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notifications load failed", e)
            } finally {
                _isLoadingNotifications.value = false
            }
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

    /**
     * Actor lists from the fork's get-post-engagement (works for ANY post), falling back to the
     * notification-stream derivation for OWN posts on older deployments.
     */
    suspend fun loadEngagement(post: KaPostDraft): EngagementLists? {
        val postId = post.remoteId ?: return null
        try {
            val rows = kaPostsService.fetchPostEngagement(postId)
            val likes = mutableListOf<EngagementEntry>()
            val dislikes = mutableListOf<EngagementEntry>()
            val reposts = mutableListOf<EngagementEntry>()
            val quotes = mutableListOf<EngagementEntry>()
            for (row in rows) {
                val address = KaPostsService.kaspaAddressFromPubkey(row.actorPubkey) ?: continue
                val entry = EngagementEntry(row.actionTxId, address, row.timestamp)
                when (row.kind) {
                    "upvote" -> likes.add(entry)
                    "downvote" -> dislikes.add(entry)
                    "repost" -> reposts.add(entry)
                    "quote" -> quotes.add(entry)
                }
            }
            return EngagementLists(likes, dislikes, reposts, quotes)
        } catch (e: Exception) {
            Log.w(TAG, "Engagement endpoint failed, falling back", e)
        }
        if (post.posterAddress != myAddress()) return null
        return try {
            val raw = kaPostsService.fetchNotifications(limit = 100)
            val likes = mutableListOf<EngagementEntry>()
            val dislikes = mutableListOf<EngagementEntry>()
            val reposts = mutableListOf<EngagementEntry>()
            val quotes = mutableListOf<EngagementEntry>()
            for (n in raw) {
                if (n.contentId != postId) continue
                val address = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
                val entry = EngagementEntry(n.id, address, n.timestamp)
                when (n.contentType) {
                    "vote" -> when (n.voteType) {
                        "upvote" -> likes.add(entry)
                        "downvote" -> dislikes.add(entry)
                    }
                    "quote" -> {
                        val text = com.kachat.app.util.KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
                        if (text.isEmpty()) reposts.add(entry) else quotes.add(entry)
                    }
                }
            }
            EngagementLists(likes, dislikes, reposts, quotes)
        } catch (e: Exception) {
            Log.w(TAG, "Engagement fallback failed", e)
            null
        }
    }

    // MARK: - Follow lists (Following / Followers with quick toggle)

    data class FollowEntry(val address: String, val pubkey: String?, val timestampMs: Long?)

    /**
     * Server list from the indexer; Following additionally merges locally-stored follows the
     * indexer hasn't caught up on. Self never appears.
     */
    suspend fun loadFollowList(followers: Boolean): List<FollowEntry> {
        val my = myAddress()
        var remote = emptyList<FollowEntry>()
        try {
            val pubkey = kaPostsService.requesterPubkey()
            remote = kaPostsService.fetchFollowList(pubkey, followers).mapNotNull { user ->
                val address = KaPostsService.kaspaAddressFromPubkey(user.userPublicKey) ?: return@mapNotNull null
                if (address == my) return@mapNotNull null
                FollowEntry(address, user.userPublicKey, user.timestamp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Follow list load failed", e)
        }
        if (!followers) {
            val seen = remote.map { it.address }.toSet()
            val localOnly = following.value
                .filter { it !in seen && it != my }
                .sorted()
                .map { FollowEntry(it, null, null) }
            remote = remote + localOnly
        }
        return remote.sortedByDescending { it.timestampMs ?: 0L }
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
            _myProfilePosts.value = kaPostsService.fetchUserPosts(pubkey)
                .mapNotNull { mapRemotePost(it) }
                .filter { it.parentRemoteId == null }
            _myProfileReplies.value = try {
                kaPostsService.fetchUserReplies(pubkey).mapNotNull { mapRemotePost(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Shared-post replies fetch failed", e)
                _myProfileReplies.value
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shared-post own-content fetch failed", e)
        }
        return findPostByRemoteId(txId)
    }
}
