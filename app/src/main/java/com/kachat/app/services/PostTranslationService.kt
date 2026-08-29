package com.kachat.app.services

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device translation for KaPosts, X-style: a post written in another language offers a
 * "Translate post" link, tapping it swaps the text in place, and the link becomes
 * "Translated from Spanish - Show original".
 *
 * Everything runs through ML Kit, which translates ON DEVICE against a downloaded language pack.
 * No post text is ever sent to a server, which matters here more than in most apps: KaPosts
 * content is public, but WHICH posts a given user chose to read closely is not, and a cloud
 * translator would leak exactly that. Mirrors iOS's `PostTranslationService`, which uses Apple's
 * Translation framework for the same reason.
 *
 * The one network cost is the first-use language pack (tens of MB per pair). It is deliberately
 * NOT restricted to Wi-Fi: the download only ever starts because the reader tapped Translate, and
 * silently refusing on cellular would look like the feature is broken. [TranslationState.Downloading]
 * exists so the UI can say what the wait is for instead of spinning mutely.
 */
@Singleton
class PostTranslationService @Inject constructor() {

    sealed interface TranslationState {
        /** Fetching the language pack; the first translation for a pair only. */
        data object Downloading : TranslationState
        data object Translating : TranslationState
        /** [sourceName] is the localized language name for the "Translated from X" line. */
        data class Translated(val text: String, val sourceName: String) : TranslationState
        data object Failed : TranslationState
    }

    /**
     * Open translators, keyed by "source>target". ML Kit translators hold native resources and
     * must be closed, so this is capped and evicts in insertion order - a reader moving through a
     * multilingual feed would otherwise accumulate one per language they touched.
     */
    private val translators = LinkedHashMap<String, Translator>()
    private val languagePacksReady = mutableSetOf<String>()

    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    /**
     * The reader's own language. A post already in it is never offered for translation.
     */
    private fun targetLanguageTag(): String? =
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language)

    /**
     * The post's language, or null when it cannot be identified confidently or ML Kit has no
     * model for it.
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
        return TranslateLanguage.fromLanguageTag(tag)
    }

    /** True when this post is worth offering a Translate link for. */
    suspend fun canOfferTranslation(text: String): Boolean {
        val target = targetLanguageTag() ?: return false
        val source = detectLanguage(text) ?: return false
        return source != target
    }

    /** Whether the pack for this pair is already on the device, so the UI can skip "Downloading". */
    fun isLanguagePackReady(source: String): Boolean {
        val target = targetLanguageTag() ?: return false
        return "$source>$target" in languagePacksReady
    }

    /** Localized name of a language tag, for "Translated from X". */
    fun displayName(languageTag: String): String =
        Locale.forLanguageTag(languageTag).getDisplayLanguage(Locale.getDefault())
            .ifBlank { languageTag }

    /**
     * Translates [text] from [source] into the reader's language. Throws on an unsupported pair or
     * a pack that could not be downloaded; the caller turns that into [TranslationState.Failed].
     */
    suspend fun translate(text: String, source: String): String {
        val target = targetLanguageTag() ?: error("No on-device model for the current locale")
        val key = "$source>$target"
        val translator = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }
        // Re-inserting keeps the most recently used at the end, so eviction drops the coldest.
        translators.remove(key)?.let { translators[key] = it }
        while (translators.size > MAX_OPEN_TRANSLATORS) {
            val coldest = translators.keys.first()
            translators.remove(coldest)?.close()
            languagePacksReady.remove(coldest)
        }
        if (key !in languagePacksReady) {
            // No requireWifi(): the reader asked for this, and refusing on cellular reads as the
            // feature being broken. The UI says a download is happening.
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            languagePacksReady += key
        }
        return translator.translate(text).await()
    }

    /** Releases every open translator. Called when KaPosts goes away or the account switches. */
    fun release() {
        translators.values.forEach { it.close() }
        translators.clear()
        languagePacksReady.clear()
    }

    private fun strippedForDetection(text: String): String =
        text.replace(URL_REGEX, " ").replace(MENTION_REGEX, " ")

    companion object {
        private const val TAG = "KaChatTranslate"
        private const val UNDETERMINED = "und"
        private const val MIN_LETTERS = 12
        private const val MAX_OPEN_TRANSLATORS = 3
        private val URL_REGEX = Regex("""https?://\S+""")
        private val MENTION_REGEX = Regex("""@[A-Za-z0-9._-]+""")
    }
}
