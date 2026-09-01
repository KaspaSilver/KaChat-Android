package com.kachat.app.services

import com.kachat.app.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The author and text behind a `kachat://kapost/<txid>` link, so a shared post previews in a chat
 * as the post itself rather than as a URL. Mirrors iOS's `KaPostLinkPreviewCache`.
 *
 * Resolved from the transaction the post IS - see [KaPostChainReader], which is also what makes
 * an old shared post openable at all. The author's name then comes from KNS, with a local contact
 * alias winning over it exactly as it does everywhere else in KaPosts.
 *
 * This is the one internal link that goes to the network, and it goes to Kaspa's own REST API
 * with a transaction id - never to the link's host. A pasted KaChat link is still never scraped
 * like a stranger's URL.
 *
 * Reached from composables through [Companion], because these cards render from several screens
 * with different view models and none of them owns this.
 */
@Singleton
class KaPostLinkPreviewService @Inject constructor(
    private val chainReader: KaPostChainReader,
    private val knsService: KnsService,
    private val chatRepository: ChatRepository,
    private val settings: com.kachat.app.repository.AppSettingsRepository,
) {
    init {
        instance = this
    }

    private suspend fun resolve(txId: String) {
        // Child Mode hides KaPosts entirely and these links no-op - so there is nothing to
        // preview, and no reason to spend a request finding out.
        if (settings.childModeEnabled.first()) return
        val record = chainReader.fetch(txId)
        if (record == null) {
            unresolvable.add(txId)
            return
        }
        val address = KaPostsService.kaspaAddressFromPubkey(record.authorPubkey)
        // Paint the post immediately with whatever name is already known, then upgrade it if the
        // author has a KNS domain nobody has looked up yet. The text is the point of the card;
        // holding it back for a name lookup would leave the URL sitting there for another round
        // trip.
        publish(txId, Preview(localName(address), address, null, snippet(record.message), record.action))
        if (address.isNullOrEmpty()) return

        // Name AND avatar, the same way the feed resolves an author it has not seen before
        // (KaPostsViewModel.ensureSenderProfileFetched): the profile belongs to a DOMAIN, so the
        // active one is picked first and its profile is what the card wears.
        val owned = knsService.getOwnedDomains(address)
        if (owned.isEmpty()) return
        val activeName = KnsService.pickActiveDomain(
            owned.mapNotNull { it.asset },
            null,
            knsService.getExplicitPrimaryDomain(address)
        )
        val active = owned.firstOrNull { it.asset == activeName }
        // Active domain first, then the others - a person can hold several and have set the
        // picture on only one of them. Bounded, because this runs for a chat bubble.
        val avatar = (listOfNotNull(active) + owned.filterNot { it.asset == activeName })
            .take(AVATAR_LOOKUP_LIMIT)
            .firstNotNullOfOrNull { asset -> asset.assetId?.let { knsService.getProfile(it)?.avatarUrl } }

        val current = previews.value[txId] ?: return
        // A contact alias the user set themselves still wins over the domain.
        val name = if (aliasFor(address) != null) current.authorName
            else activeName?.let { strippingKasSuffix(it) } ?: current.authorName
        publish(txId, current.copy(authorName = name, authorAvatarUrl = avatar))
    }

    private suspend fun localName(address: String?): String? {
        if (address.isNullOrEmpty()) return null
        aliasFor(address)?.let { return strippingKasSuffix(it) }
        return address.takeLast(10)
    }

    private suspend fun aliasFor(address: String): String? = try {
        chatRepository.getContacts().first()
            .firstOrNull { it.id == address }
            ?.alias
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** "alice.kas" reads better as just "alice" - the .kas is implied everywhere in KaPosts. */
    private fun strippingKasSuffix(domain: String): String {
        val trimmed = domain.trim()
        return if (trimmed.lowercase().endsWith(".kas")) trimmed.dropLast(4) else trimmed
    }

    private fun snippet(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length > SNIPPET_MAX_LENGTH) trimmed.take(SNIPPET_MAX_LENGTH) + "…" else trimmed
    }

    private fun publish(txId: String, preview: Preview) {
        val current = previews.value
        val next = if (current.size >= LIMIT) {
            // Bounded, oldest-first - a chat scrolled for long enough must not grow this forever.
            current.entries.drop(current.size - LIMIT + 1).associate { it.key to it.value }
        } else {
            current
        }
        previews.value = next + (txId to preview)
    }

    /**
     * [snippet] is already card-sized, and empty for a comment-free quote. [action] is "post",
     * "reply" or "quote" - the card says which.
     */
    data class Preview(
        val authorName: String?,
        val authorAddress: String?,
        /** The author's KNS avatar, once their profile has been fetched. */
        val authorAvatarUrl: String?,
        val snippet: String,
        val action: String,
    )

    companion object {
        private const val SNIPPET_MAX_LENGTH = 240
        private const val LIMIT = 512
        private const val AVATAR_LOOKUP_LIMIT = 3

        @Volatile
        private var instance: KaPostLinkPreviewService? = null

        /** Resolved posts by id. Static so a card can read it before Hilt has built anything. */
        val previews = MutableStateFlow<Map<String, Preview>>(emptyMap())

        private val inFlight = mutableSetOf<String>()

        /**
         * Ids the chain had nothing for. Retrying on every scroll would hammer the REST API for a
         * post that is never going to resolve - a mistyped link, a pruned node, another app's tx.
         */
        private val unresolvable = mutableSetOf<String>()

        /**
         * Safe to call on every composition: an entry already held, a fetch already running and
         * an id already known to be unresolvable all return immediately.
         */
        suspend fun load(txId: String) {
            if (txId.isEmpty() || previews.value.containsKey(txId)) return
            synchronized(inFlight) {
                if (txId in unresolvable || !inFlight.add(txId)) return
            }
            try {
                instance?.resolve(txId)
            } finally {
                synchronized(inFlight) { inFlight.remove(txId) }
            }
        }
    }
}
