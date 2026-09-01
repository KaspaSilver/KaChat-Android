package com.kachat.app.services

import android.util.Log
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaPostsProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The author and text behind a `kachat://kapost/<txid>` link, so a shared post previews in a chat
 * as the post itself rather than as a URL. Mirrors iOS's `KaPostLinkPreviewCache`.
 *
 * Resolved from the transaction the post IS. The K indexer has no single-post lookup
 * (`get-post?id=` is still listed as NEEDED in KAPOSTS_INDEXER.md), and a post someone shares is
 * usually outside the feed window, so the API cannot answer for it at all. The chain always can:
 * the post id is the transaction id, and the payload holds the same bytes the indexer read. See
 * [KaPostsProtocol.parseChainPayload]. The author's name then comes from KNS, with a local
 * contact alias winning over it exactly as it does everywhere else in KaPosts.
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
    private val networkService: NetworkService,
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
        val api = networkService.kaspaRestApi.value ?: return
        val payload = try {
            api.getTransactionPayload(txId).payload
        } catch (e: Exception) {
            Log.w(TAG, "No transaction for post $txId: ${e.message}")
            null
        }
        val decoded = payload?.takeIf { it.isNotBlank() }?.let { hexToString(it) }
        val record = decoded?.let { KaPostsProtocol.parseChainPayload(it) }
        if (record == null) {
            unresolvable.add(txId)
            return
        }
        val address = KaPostsService.kaspaAddressFromPubkey(record.authorPubkey)
        // Paint the post immediately with whatever name is already known, then upgrade it if the
        // author has a KNS domain nobody has looked up yet. The text is the point of the card;
        // holding it back for a name lookup would leave the URL sitting there for another round
        // trip.
        publish(txId, Preview(localName(address), address, snippet(record.message), record.action))
        if (address.isNullOrEmpty()) return
        val domain = try {
            knsService.reverseResolve(address)
        } catch (e: Exception) {
            null
        }
        if (domain.isNullOrBlank()) return
        val current = previews.value[txId] ?: return
        // A contact alias the user set themselves still wins over the domain.
        if (aliasFor(address) != null) return
        publish(txId, current.copy(authorName = strippingKasSuffix(domain)))
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

    private fun hexToString(hex: String): String? = try {
        val clean = hex.trim()
        if (clean.length % 2 != 0) null
        else String(ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }, Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /**
     * [snippet] is already card-sized, and empty for a comment-free quote. [action] is "post",
     * "reply" or "quote" - the card says which.
     */
    data class Preview(
        val authorName: String?,
        val authorAddress: String?,
        val snippet: String,
        val action: String,
    )

    companion object {
        private const val TAG = "KaPostLinkPreview"
        private const val SNIPPET_MAX_LENGTH = 240
        private const val LIMIT = 512

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
