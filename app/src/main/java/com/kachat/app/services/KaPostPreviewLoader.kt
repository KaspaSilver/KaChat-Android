package com.kachat.app.services

import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.util.KaspaAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What a KaPost link's card shows once the post is found: who posted it and what it says. */
data class KaPostPreview(
    /** The poster's address on the network the app runs on, null if their key didn't read. */
    val authorAddress: String?,
    val text: String,
)

/**
 * Looks the post a KaPost link names up on the KaChat indexer (`get-post?id=`, the lookup KaPost
 * itself uses), for the link's card in a chat (iOS a8a5807a `KaPostPreviewLoader`). One answer per
 * post per run, shared by every card; a post not found is asked again after five minutes at most.
 */
@Singleton
class KaPostPreviewLoader @Inject constructor(
    okHttpClient: OkHttpClient,
    private val settingsRepository: AppSettingsRepository,
) {
    private val client = okHttpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private val cache = HashMap<String, KaPostPreview>()
    private val misses = HashMap<String, Long>()
    private val inFlight = HashMap<String, CompletableDeferred<KaPostPreview?>>()

    fun cached(txId: String): KaPostPreview? = synchronized(cache) { cache[txId] }

    suspend fun load(txId: String): KaPostPreview? {
        val (deferred, owner) = lock.withLock {
            synchronized(cache) { cache[txId] }?.let { return it }
            misses[txId]?.let { if (System.currentTimeMillis() - it < MISS_RETRY_MS) return null }
            inFlight[txId]?.let { return@withLock it to false }
            CompletableDeferred<KaPostPreview?>().also { inFlight[txId] = it } to true
        }
        if (!owner) return deferred.await()
        val result = runCatching {
            val base = settingsRepository.indexerUrl.first().trim()
            fetch(txId, base)?.let { (authorKey, text) ->
                KaPostPreview(authorAddress = authorKey?.let(::authorAddress), text = text)
            }
        }.getOrNull()
        lock.withLock {
            inFlight.remove(txId)
            if (result != null) synchronized(cache) { cache[txId] = result } else misses[txId] = System.currentTimeMillis()
        }
        deferred.complete(result)
        return result
    }

    private suspend fun fetch(txId: String, base: String): Pair<String?, String>? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        val url = base.trimEnd('/').toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("get-post")
            ?.addQueryParameter("id", txId)
            ?.addQueryParameter("requesterPubkey", PLACEHOLDER_REQUESTER)
            ?.build() ?: return@withContext null
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (response.code != 200) return@withContext null
            val post = JSONObject(response.body?.string() ?: return@withContext null).getJSONObject("post")
            val content = post.getString("postContent")
            val bytes = runCatching { java.util.Base64.getDecoder().decode(content) }.getOrNull() ?: return@withContext null
            val text = cleanText(String(bytes, Charsets.UTF_8))
            if (text.isEmpty()) return@withContext null
            val authorKey = post.optString("userPublicKey").takeIf { post.has("userPublicKey") && !post.isNull("userPublicKey") }
            authorKey to text
        }
    }

    companion object {
        private const val MISS_RETRY_MS = 300_000L
        /** The indexer wants a requester key, which only marks per-reader fields (your vote). A
         *  fixed placeholder keeps who is reading the link out of the request. */
        private val PLACEHOLDER_REQUESTER = "02" + "0".repeat(64)
        /** As much as the card can show, and what the link site puts in its preview. */
        private const val TEXT_LIMIT = 300
        private val NEWLINES = Regex("[\n\u000B\u000C\r\u0085  ]")

        /** Someone else's text, shown in a small card: one paragraph (line breaks become spaces),
         *  no control or direction marks (KaPost's invisible marker among them), at most
         *  [TEXT_LIMIT] characters. */
        fun cleanText(raw: String): String {
            // Every newline character is a break, as Swift's `.newlines` splits them.
            val flattened = raw.replace(NEWLINES, " ")
            val out = StringBuilder()
            var i = 0
            while (i < flattened.length) {
                val cp = flattened.codePointAt(i)
                i += Character.charCount(cp)
                val type = Character.getType(cp)
                if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) continue
                if (cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x2069 || cp == 0xFEFF) continue
                out.appendCodePoint(cp)
            }
            val cleaned = out.toString().trim()
            val count = cleaned.codePointCount(0, cleaned.length)
            return if (count > TEXT_LIMIT) cleaned.substring(0, cleaned.offsetByCodePoints(0, TEXT_LIMIT)) + "…" else cleaned
        }

        /** A poster's compressed public key (66 hex) as their address: posts are signed with the
         *  key a chatting address is made of. */
        fun authorAddress(hex: String): String? {
            if (hex.length != 66 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val key = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val address = runCatching { KaspaAddress.encode("kaspa", 0, key.copyOfRange(1, 33)) }.getOrNull() ?: return null
            return KaspaAddress.onActiveNetwork(address)
        }
    }
}
