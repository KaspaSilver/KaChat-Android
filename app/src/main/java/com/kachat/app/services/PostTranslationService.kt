package com.kachat.app.services

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translation for KaPosts, X-style: a post written in another language offers a "Translate post"
 * link, tapping it swaps the text in place, and the link becomes "Translated from Spanish - Show
 * original".
 *
 * The translation itself happens on the KaChat server (see `TRANSLATION_SERVICE.md`), the way X
 * does it, rather than on the device. On-device translation - ML Kit here, Apple's Translation
 * framework on iOS - was private but cost the reader a language-pack download of tens of megabytes
 * before the first translation finished, and re-translated the same post on every device that read
 * it. A KaPost is immutable, so the server translates it once and serves that answer to everyone
 * forever. Mirrors iOS's `PostTranslationService`.
 *
 * The trade, stated plainly because the on-device design was chosen deliberately to avoid it: post
 * CONTENT is public (it is on the blockDAG), but WHICH posts a reader stopped to translate now
 * reaches the server. The request carries no identity of any kind - no pubkey, no token, no account
 * id - and the server is specified not to log bodies and to warm its cache ahead of demand, so most
 * requests are answered without a translation engine ever seeing them.
 *
 * Language IDENTIFICATION stays on the device (ML Kit's language-id, which is bundled and needs no
 * download). Deciding whether to offer the link at all is asked for every post that scrolls past,
 * and asking a server that would be a request per post.
 */
@Singleton
class PostTranslationService @Inject constructor(
    private val settings: com.kachat.app.repository.AppSettingsRepository,
) {

    sealed interface TranslationState {
        data object Translating : TranslationState
        /** [sourceName] is the localized language name for the "Translated from X" line. */
        data class Translated(val text: String, val sourceName: String) : TranslationState
        /**
         * Retryable: a dropped connection, a timeout, a server that was briefly down. The link
         * stays live and says so.
         */
        data object Failed : TranslationState
        /**
         * Terminal for this post and this reader: the pair is not served, the post is too long,
         * the text was already in the reader's language. Retrying cannot change the answer, so the
         * affordance says what happened instead of inviting a pointless second tap.
         */
        data class Unavailable(val reason: String) : TranslationState
    }

    /** Raised for anything the server told us. [terminal] marks the answers a retry cannot change. */
    class TranslationException(
        message: String,
        val code: String? = null,
        val terminal: Boolean = false,
    ) : Exception(message) {
        /** What the reader is told under the post. */
        val readerMessage: String
            get() = if (code == "UNSUPPORTED_PAIR") "Not available in your language"
            else message ?: "Translation unavailable"
    }

    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * The locale the reader actually reads KaChat in: Settings > Language when it has been set,
     * and the device's own locale only for "System".
     *
     * Deliberately not `Locale.getDefault()` alone. The in-app override is applied through
     * `AppCompatDelegate.setApplicationLocales`, which reliably re-resolves resources but is not
     * guaranteed to move the process-wide JVM default that a `@Singleton` with no Activity context
     * would read here. Getting this wrong is what left a reader who picked Vietnamese on an
     * English phone with no Translate link on English posts at all (source == target, so nothing
     * was offered) and Vietnamese posts translated INTO English.
     */
    private fun readerLocale(): Locale =
        AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()

    /** The reader's language, as the bare subtag the server expects ("en", not "en-GB"). Public
     *  because callers cache per-post "worth offering?" answers and have to throw them away when
     *  the reader changes language. */
    fun targetLanguage(): String? = readerLocale().language.takeIf { it.isNotBlank() }

    /**
     * The post's language, or null when it cannot be identified confidently.
     *
     * URLs and @mentions are stripped first: a post that is mostly a link otherwise identifies as
     * whatever language the URL's letters resemble. Below [MIN_LETTERS] letters, identification is
     * guesswork - emoji-only and "gm" posts fall out here - and a wrong guess is worse than no
     * offer, because it puts a "Translate from Portuguese" link under readable English.
     */
    suspend fun detectLanguage(text: String): String? {
        val stripped = strippedForDetection(text)
        if (stripped.count { it.isLetter() } < MIN_LETTERS) return null
        val tag = try {
            languageIdentifier.identifyLanguage(stripped).await()
        } catch (e: Exception) {
            Log.w(TAG, "Language identification failed", e)
            return null
        }
        if (tag == UNDETERMINED) return null
        // ML Kit returns BCP-47 with a region for some languages ("zh-Hans"); the server takes the
        // bare subtag.
        return tag.substringBefore('-').takeIf { it.isNotBlank() }
    }

    /**
     * True when this post is worth offering a Translate link for: identifiable, not already in the
     * reader's language, and a pair the configured service can actually serve.
     *
     * [detectedSource] lets a caller that has already identified the language pass it in rather
     * than paying for a second ML Kit round trip on the same text.
     */
    suspend fun canOfferTranslation(text: String, detectedSource: String? = null): Boolean {
        val target = targetLanguage() ?: return false
        val source = detectedSource ?: detectLanguage(text) ?: return false
        if (source == target) return false
        val supported = supportedLanguages() ?: return true
        return source in supported.source && target in supported.target
    }

    /** Localized name of a language tag, for "Translated from X", in the reader's own language. */
    fun displayName(languageTag: String): String =
        Locale.forLanguageTag(languageTag).getDisplayLanguage(readerLocale())
            .ifBlank { languageTag }

    /** The translated text plus the source language the SERVER detected, which beats our guess. */
    data class Result(val text: String, val sourceLanguage: String?)

    /**
     * Translates [text] into the reader's language.
     *
     * [postId] is the txid where there is one. The server caches by it, so a post someone else
     * already translated into this language comes back without a translation engine running at
     * all; a post with no txid (a local session post) is translated but not cached.
     *
     * Throws on any failure; the caller turns that into [TranslationState.Failed], or
     * [TranslationState.Unavailable] for a [TranslationException] marked terminal.
     */
    suspend fun translate(text: String, postId: String?): Result = withContext(Dispatchers.IO) {
        val target = targetLanguage() ?: throw TranslationException("No language for the current locale")
        val base = settings.translationServiceUrl.first().trimEnd('/')

        val post = JSONObject().put("text", text)
        if (!postId.isNullOrEmpty()) post.put("id", postId)
        val body = JSONObject()
            .put("target", target)
            .put("posts", JSONArray().put(post))

        val request = Request.Builder()
            .url("$base/translate")
            // Deliberately no identity header of any kind - see the note on this class.
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(payload) }.getOrNull()
                val message = json?.optString("error")?.takeIf { it.isNotBlank() }
                val code = json?.optString("code")?.takeIf { it.isNotBlank() }
                throw TranslationException(message ?: "HTTP ${response.code}", code, code in TERMINAL_CODES)
            }
            val entry = JSONObject(payload).optJSONArray("translations")?.optJSONObject(0)
                ?: throw TranslationException("Unexpected response from the translation service")
            entry.optString("error").takeIf { it.isNotBlank() }?.let { message ->
                val code = entry.optString("code").takeIf { it.isNotBlank() }
                throw TranslationException(message, code, code in TERMINAL_CODES)
            }
            // The server returns the text unchanged when it decides the post was already in the
            // reader's language - our detection is a guess and is sometimes wrong. Showing the
            // same text back under a "Translated from" line would look broken, and inviting a
            // retry is worse: the second tap gets the same answer.
            if (entry.optBoolean("untranslated", false)) {
                throw TranslationException("Already in your language", "UNTRANSLATED", terminal = true)
            }
            val translated = entry.optString("text").takeIf { it.isNotBlank() }
                ?: throw TranslationException("Unexpected response from the translation service")
            Result(translated, entry.optString("source").takeIf { it.isNotBlank() })
        }
    }

    // MARK: - Supported languages

    private data class SupportedLanguages(val source: Set<String>, val target: Set<String>)

    /** Base URL to what it answered. The inner value is null for a deployment that does not
     *  implement the endpoint, cached so we ask that question once and not once per post. */
    @Volatile
    private var supportedCache: Pair<String, SupportedLanguages?>? = null
    private val supportedMutex = Mutex()

    /**
     * What the configured service can actually translate (`GET /translate/languages`), so a reader
     * whose language the deployment does not serve is never offered a link that can only fail.
     *
     * Null means "we do not know", either because the endpoint is absent or the request failed.
     * Both fall back to offering the link anyway, which is what `TRANSLATION_SERVICE.md`
     * specifies.
     */
    private suspend fun supportedLanguages(): SupportedLanguages? {
        val base = settings.translationServiceUrl.first().trimEnd('/')
        supportedCache?.let { if (it.first == base) return it.second }
        return supportedMutex.withLock {
            supportedCache?.let { if (it.first == base) return@withLock it.second }
            val fetched = fetchSupportedLanguages(base)
            supportedCache = base to fetched
            fetched
        }
    }

    private suspend fun fetchSupportedLanguages(base: String): SupportedLanguages? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$base/translate/languages").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val json = JSONObject(response.body?.string().orEmpty())
                    val source = json.optJSONArray("source").toLowerSet()
                    val target = json.optJSONArray("target").toLowerSet()
                    if (source.isEmpty() || target.isEmpty()) null
                    else SupportedLanguages(source, target)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Supported-language list unavailable", e)
                null
            }
        }

    private fun JSONArray?.toLowerSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() }?.lowercase() }.toSet()
    }

    private fun strippedForDetection(text: String): String =
        text.replace(URL_REGEX, " ").replace(MENTION_REGEX, " ")

    companion object {
        private const val TAG = "KaChatTranslate"
        private const val UNDETERMINED = "und"
        private const val MIN_LETTERS = 12
        /**
         * Generous, because the FIRST request for a language pair can make the server load that
         * pair's model. Everyone after that is answered from its cache in well under a second, so
         * the only reader who ever waits this long is the one who asked first. A 20s cap here
         * turned that one reader's request into a failure banner.
         */
        private const val TIMEOUT_SECONDS = 45L
        /** Server answers a second tap cannot change. */
        private val TERMINAL_CODES = setOf(
            "UNSUPPORTED_PAIR", "TEXT_TOO_LONG", "INVALID_POST_ID", "MISSING_PARAMETER",
        )
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val URL_REGEX = Regex("""https?://\S+""")
        private val MENTION_REGEX = Regex("""@[A-Za-z0-9._-]+""")
    }
}
