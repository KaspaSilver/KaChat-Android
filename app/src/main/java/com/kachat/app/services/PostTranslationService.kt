package com.kachat.app.services

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
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
        data object Failed : TranslationState
    }

    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** The reader's language, as the bare subtag the server expects ("en", not "en-GB"). */
    private fun targetLanguage(): String? = Locale.getDefault().language.takeIf { it.isNotBlank() }

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

    /** True when this post is worth offering a Translate link for. */
    suspend fun canOfferTranslation(text: String): Boolean {
        val target = targetLanguage() ?: return false
        val source = detectLanguage(text) ?: return false
        return source != target
    }

    /** Localized name of a language tag, for "Translated from X". */
    fun displayName(languageTag: String): String =
        Locale.forLanguageTag(languageTag).getDisplayLanguage(Locale.getDefault())
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
     * Throws on any failure; the caller turns that into [TranslationState.Failed].
     */
    suspend fun translate(text: String, postId: String?): Result {
        val target = targetLanguage() ?: error("No language for the current locale")
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
                val message = runCatching { JSONObject(payload).optString("error") }.getOrNull()
                error(message?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}")
            }
            val entry = JSONObject(payload).optJSONArray("translations")?.optJSONObject(0)
                ?: error("Unexpected response from the translation service")
            entry.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
            // The server returns the text unchanged when it decides the post was already in the
            // reader's language - our detection is a guess and is sometimes wrong. Showing the
            // same text back under a "Translated from" line would look broken.
            if (entry.optBoolean("untranslated", false)) error("Already in your language")
            val translated = entry.optString("text").takeIf { it.isNotBlank() }
                ?: error("Unexpected response from the translation service")
            return Result(translated, entry.optString("source").takeIf { it.isNotBlank() })
        }
    }

    private fun strippedForDetection(text: String): String =
        text.replace(URL_REGEX, " ").replace(MENTION_REGEX, " ")

    companion object {
        private const val TAG = "KaChatTranslate"
        private const val UNDETERMINED = "und"
        private const val MIN_LETTERS = 12
        private const val TIMEOUT_SECONDS = 20L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val URL_REGEX = Regex("""https?://\S+""")
        private val MENTION_REGEX = Regex("""@[A-Za-z0-9._-]+""")
    }
}
