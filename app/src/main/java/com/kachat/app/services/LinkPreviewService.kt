package com.kachat.app.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Open Graph metadata scraped from a message link, for rendering a rich preview card. */
data class LinkPreviewData(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    /** Set only for Nextcloud public-share links: "image"/"video"/"audio"/"pdf"/"file" (generic
     *  fallback — a valid share ALWAYS gets a kind, never null). Tells the card this link is a
     *  directly shareable file and which kind, so tapping opens the in-app viewer / system player
     *  instead of the browser. Mirrors iOS's `LinkPreviewData.nextcloudMedia`. */
    val nextcloudMedia: String? = null,
    /** The share's raw-file `/download` URL (streams via range requests) — the viewer's source. */
    val mediaDownloadUrl: String? = null,
    /** The shared file's Content-Length from the HEAD probe — the attachment card's size caption. */
    val mediaByteSize: Long? = null
)

/**
 * Fetches Open Graph preview metadata for links sent in chat messages (private, group, and
 * broadcast rooms alike). Each recipient's own device does this fetch when the message
 * renders, rather than the sender embedding preview data in the encrypted message payload, so
 * link previews never bloat the on-chain/indexer payload.
 *
 * Plain object, not Hilt-injected - [com.kachat.app.ui.screens.MessageBubble] and
 * `GroupMessageBubble` are presentational composables with no ViewModel threaded through, so this
 * mirrors the existing no-DI utility pattern [com.kachat.app.util.TextLinkify] already uses,
 * rather than changing those composables' signatures. Owns its own short-timeout client, separate
 * from [com.kachat.app.di.AppModule]'s REST-API client - arbitrary user-supplied URLs need
 * tighter timeouts than trusted API hosts.
 */
object LinkPreviewService {
    private const val FETCH_TIMEOUT_SECONDS = 8L
    private const val MAX_BODY_BYTES = 1_000_000L
    private const val CACHE_LIMIT = 2_048

    private val client = OkHttpClient.Builder()
        .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // `null` value = "fetched, but no preview data found" - still worth caching so a bad/plain
    // link isn't refetched on every scroll. Bounded FIFO eviction, not LRU - simplicity over
    // optimality for a cosmetic, cheap-to-refetch-on-relaunch cache.
    private val cache = LinkedHashMap<String, LinkPreviewData?>()
    private val cacheLock = Any()

    private val titleTagRegex = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)

    private val youTubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")

    /** A real desktop-browser User-Agent. A self-identifying bot UA (the old value) makes sites
     *  like Instagram serve a login/consent wall whose HTML has a title but no post-specific
     *  `og:image`; a browser UA gets the real page. Shared with the image loader (see
     *  [com.kachat.app.ui.screens.LinkPreviewCard]) so image CDNs (cdninstagram/fbcdn) that reject
     *  non-browser requests don't 403. Mirrors iOS's `LinkPreviewService.browserUserAgent`. */
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"

    /** Meta's own link-scraper User-Agent. Instagram/Facebook serve full Open Graph tags -
     *  including `og:image` - to this crawler, whereas a browser UA increasingly gets a login wall
     *  with no post image. Used for the scrape on Meta hosts. Mirrors iOS's
     *  `facebookExternalHitUserAgent`. */
    const val FACEBOOK_EXTERNAL_HIT_UA =
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    /** Hosts that gate `og:image` behind Meta's crawler UA (a plain/bot UA gets a login wall). */
    private val metaScrapeHosts = setOf(
        "instagram.com", "www.instagram.com", "m.instagram.com",
        "facebook.com", "www.facebook.com", "m.facebook.com", "fb.watch"
    )

    /** `https://host/s/TOKEN` (or `/index.php/s/TOKEN`, token 10+ url-safe chars). */
    private val nextcloudSharePathRegex = Regex("""^(/index\.php)?/s/([A-Za-z0-9_-]{10,})/?$""")

    data class NextcloudShareEndpoints(val downloadUrl: String, val previewUrl: String)

    /** A Nextcloud public share's raw-file `/download` and thumbnail `/preview` endpoints
     *  (query/fragment stripped); null for any other URL. */
    fun nextcloudShareEndpoints(url: String): NextcloudShareEndpoints? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val match = nextcloudSharePathRegex.find(httpUrl.encodedPath) ?: return null
        val prefix = if (match.groupValues[1].isNotEmpty()) "/index.php" else ""
        val base = httpUrl.newBuilder()
            .encodedPath("$prefix/s/${match.groupValues[2]}")
            .query(null)
            .fragment(null)
            .build()
            .toString()
        return NextcloudShareEndpoints(downloadUrl = "$base/download", previewUrl = "$base/preview")
    }

    suspend fun fetchPreview(url: String): LinkPreviewData? {
        synchronized(cacheLock) {
            if (cache.containsKey(url)) return cache[url]
        }

        val result = withContext(Dispatchers.IO) { fetchAndParse(url) }

        synchronized(cacheLock) {
            if (!cache.containsKey(url) && cache.size >= CACHE_LIMIT) {
                val oldestKey = cache.keys.firstOrNull()
                if (oldestKey != null) cache.remove(oldestKey)
            }
            cache[url] = result
        }
        return result
    }

    private fun fetchAndParse(url: String): LinkPreviewData? {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return null

        // Nextcloud public shares are media files, not pages — no Open Graph to scrape. Type
        // comes from a HEAD on the raw-file endpoint instead (see fetchNextcloudPreview).
        nextcloudShareEndpoints(url)?.let { return fetchNextcloudPreview(url, it) }

        // YouTube serves a cookie-consent-wall page (no Open Graph tags at all) to plain scraper
        // requests instead of the real video page, so the generic scrape below never finds
        // anything for a youtube.com/youtu.be link. YouTube's own oEmbed endpoint is built
        // exactly for this (no consent wall, no API key needed).
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
        if (host != null && host in youTubeHosts) {
            fetchYouTubeOEmbed(url)?.let { return it }
            // Fall through to the generic scrape only if oEmbed itself failed (e.g. a
            // private/deleted video) - unlikely to succeed either, but no harm trying.
        }

        // Instagram/Facebook serve a login wall (title/description but no post `og:image`) to a
        // plain/bot UA; Meta's crawler UA gets the full Open Graph tags. Everything else gets a
        // real browser UA (some CDNs 403 a self-identifying bot).
        val scrapeUserAgent = if (host != null && host in metaScrapeHosts) FACEBOOK_EXTERNAL_HIT_UA else BROWSER_USER_AGENT
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", scrapeUserAgent)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val source = response.body?.source() ?: return null

                val buffer = Buffer()
                while (buffer.size < MAX_BODY_BYTES) {
                    val read = source.read(buffer, MAX_BODY_BYTES - buffer.size)
                    if (read == -1L) break
                }
                val html = buffer.readString(Charsets.UTF_8)
                parseHtml(html, url)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchYouTubeOEmbed(url: String): LinkPreviewData? {
        val oEmbedUrl = "https://www.youtube.com/oembed".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("url", url)
            ?.addQueryParameter("format", "json")
            ?.build() ?: return null

        return try {
            val request = Request.Builder()
                .url(oEmbedUrl)
                .header("User-Agent", "Mozilla/5.0 (compatible; KaChatLinkPreview/1.0)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val title = json.optString("title").takeIf { it.isNotEmpty() }
                val thumbnailUrl = json.optString("thumbnail_url").takeIf { it.isNotEmpty() }
                if (title == null && thumbnailUrl == null) return null

                LinkPreviewData(
                    url = url,
                    title = title,
                    description = null,
                    imageUrl = thumbnailUrl,
                    siteName = "YouTube"
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The share link itself carries no file type, so HEAD the `/download` URL and branch on
     * `Content-Type`. Non-media shares return null, which renders as a plain link. The card's
     * poster is the share's `/preview` thumbnail; the full-quality fetch/stream only happens
     * when the user taps the card.
     */
    private fun fetchNextcloudPreview(url: String, endpoints: NextcloudShareEndpoints): LinkPreviewData? {
        return try {
            val request = Request.Builder().url(endpoints.downloadUrl).head().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val contentType = (response.header("Content-Type") ?: "").lowercase()
                // Every valid share gets a kind — "file" is the generic fallback for docs,
                // archives, and anything else, so a share never renders as a bare link.
                val kind = when {
                    contentType.startsWith("image/") -> "image"
                    contentType.startsWith("video/") -> "video"
                    contentType.startsWith("audio/") -> "audio"
                    contentType.contains("pdf") -> "pdf"
                    else -> "file"
                }
                val filename = filenameFromContentDisposition(response.header("Content-Disposition"))
                val fallbackTitle = when (kind) {
                    "image" -> "Photo"
                    "video" -> "Video"
                    "audio" -> "Audio"
                    "pdf" -> "PDF"
                    else -> "File"
                }
                val host = runCatching { java.net.URI(url).host }.getOrNull()
                LinkPreviewData(
                    url = url,
                    title = filename ?: fallbackTitle,
                    description = null,
                    imageUrl = endpoints.previewUrl,
                    siteName = if (host != null) "Nextcloud · $host" else "Nextcloud",
                    nextcloudMedia = kind,
                    mediaDownloadUrl = endpoints.downloadUrl,
                    mediaByteSize = response.header("Content-Length")?.toLongOrNull()
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads a (public) share file to app-private cache for local rendering — the in-app PDF
     * viewer's source. Its own client without [FETCH_TIMEOUT_SECONDS]'s overall call cap: a
     * multi-MB document on a home server's uplink legitimately takes longer than a page scrape.
     * Null on any failure or if the file exceeds [maxBytes].
     */
    suspend fun downloadToCacheFile(
        context: android.content.Context,
        url: String,
        maxBytes: Long = 50_000_000L
    ): java.io.File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                if (body.contentLength() > maxBytes) return@withContext null
                val dir = java.io.File(context.cacheDir, "nextcloud_previews").apply { mkdirs() }
                val file = java.io.File(dir, "share-${url.hashCode()}.bin")
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (file.length() > maxBytes) {
                    file.delete()
                    null
                } else {
                    file
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Pulls the shared file's name out of `Content-Disposition: attachment; filename="x.jpg"`
     *  (or the RFC 5987 `filename*=UTF-8''x.jpg` form) for the card title. */
    private fun filenameFromContentDisposition(header: String?): String? {
        if (header == null) return null
        Regex("""filename\*=UTF-8''([^;]+)""").find(header)?.let { match ->
            val raw = match.groupValues[1].trim()
            return runCatching { android.net.Uri.decode(raw) }.getOrDefault(raw).takeIf { it.isNotEmpty() }
        }
        Regex("""filename="([^"]+)"""").find(header)?.let { match ->
            return match.groupValues[1].takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun parseHtml(html: String, url: String): LinkPreviewData? {
        val title = metaContent("og:title", html, useProperty = true) ?: titleTag(html)
        val description = metaContent("og:description", html, useProperty = true) ?: metaContent("description", html, useProperty = false)
        val imageUrl = metaContent("og:image", html, useProperty = true)?.let { resolveUrl(it, url) }
        val siteName = metaContent("og:site_name", html, useProperty = true) ?: runCatching { java.net.URI(url).host }.getOrNull()

        if (title == null && description == null && imageUrl == null) return null

        return LinkPreviewData(
            url = url,
            title = title?.decodeHtmlEntities(),
            description = description?.decodeHtmlEntities(),
            imageUrl = imageUrl,
            siteName = siteName
        )
    }

    /** Matches `<meta property="og:title" content="...">` in either attribute order, single or
     *  double quotes - real-world OG tags aren't consistent about ordering/quoting. */
    private fun metaContent(tagValue: String, html: String, useProperty: Boolean): String? {
        val attribute = if (useProperty) "property" else "name"
        val escaped = Regex.escape(tagValue)
        val patterns = listOf(
            """<meta[^>]+$attribute=["']$escaped["'][^>]+content=["']([^"']*)["']""",
            """<meta[^>]+content=["']([^"']*)["'][^>]+$attribute=["']$escaped["']"""
        )
        for (pattern in patterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
            val raw = match?.groupValues?.getOrNull(1)?.trim()
            if (!raw.isNullOrEmpty()) return raw
        }
        return null
    }

    private fun titleTag(html: String): String? {
        val raw = titleTagRegex.find(html)?.groupValues?.getOrNull(1)?.trim()
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun resolveUrl(raw: String, base: String): String {
        return try {
            java.net.URI(base).resolve(raw).toString()
        } catch (e: Exception) {
            raw
        }
    }

    private fun String.decodeHtmlEntities(): String {
        return this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
