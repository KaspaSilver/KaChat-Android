package com.kachat.app.services

import android.content.Context
import androidx.annotation.StringRes
import coil.imageLoader
import com.kachat.app.R
import com.kachat.app.services.kachatnames.KachatNamesRegistry
import com.kachat.app.services.kachatnames.KachatProfileCache
import com.kachat.app.services.kachatnames.KachatSocialImageResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app keeps on disk that it can always fetch or rebuild, and how much of it there is.
 *
 * Deliberately excludes anything the user would lose by clearing: messages, contacts, keys and
 * settings are not cache, however much space they take. Every category here is re-fetched or
 * re-derived on demand, so clearing costs a little bandwidth and nothing else - which is exactly
 * what makes it safe to offer as a button.
 *
 * The rows are iOS CacheManager's (5e408f7) - Profiles, Web Responses, Temporary Files - except
 * iOS's Contact Photos: Android never copies address-book photos, it shows them from the address
 * book itself (`ContactEntity.systemContactPhotoUri`), so there is nothing of them to measure.
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    /** Not a directory - its records live in SharedPreferences; see [Category.PROFILES]. */
    private val knsProfileCache: KnsProfileCacheStore,
    /** The profile records (identities) held in memory too - reset with the folder. Lazy: the
     *  registry is built at startup anyway, and this keeps the graph acyclic. */
    private val kachatRegistry: dagger.Lazy<KachatNamesRegistry>,
    private val kachatSocialImages: dagger.Lazy<KachatSocialImageResolver>,
) {
    enum class Category(@StringRes val title: Int, @StringRes val detail: Int) {
        PROFILES(R.string.cache_profiles, R.string.cache_profiles_detail),
        WEB_RESPONSES(R.string.cache_web_responses, R.string.cache_web_responses_detail),
        TEMPORARY_FILES(R.string.cache_temporary_files, R.string.cache_temporary_files_detail),
    }

    /** Coil owns its own directory; the rest are ours by name. */
    private fun directories(category: Category): List<File> = when (category) {
        // The avatar and banner images (Coil's image cache - Android has one for every picture,
        // so it is counted here, where most of it belongs), and the profile records and lookups
        // (KachatProfileCache).
        Category.PROFILES -> listOfNotNull(
            context.imageLoader.diskCache?.directory?.toFile(),
            KachatProfileCache.directory(context),
        )
        Category.WEB_RESPONSES -> listOf(File(context.cacheDir, "nextcloud_previews"))
        Category.TEMPORARY_FILES -> listOf(
            File(context.cacheDir, "shared_images"),
            File(context.cacheDir, "camera_captures"),
            File(context.cacheDir, "portfolio_exports"),
            File(context.cacheDir, "chat_exports"),
            File(context.cacheDir, "diagnostics_exports"),
        )
    }

    /**
     * Loose files sitting directly in cacheDir - voice playback scratch, and anything else written
     * without a folder of its own. Counted under Temporary Files. Subdirectories are skipped so
     * they are not double-counted against the categories that own them.
     */
    private fun looseTempFiles(): List<File> =
        context.cacheDir.listFiles()?.filter { it.isFile } ?: emptyList()

    suspend fun size(category: Category): Long = withContext(Dispatchers.IO) {
        var total = directories(category).sumOf { directorySize(it) }
        if (category == Category.TEMPORARY_FILES) {
            total += looseTempFiles().sumOf { it.length() }
        }
        if (category == Category.PROFILES) {
            total += knsProfileCache.approximateSizeBytes()
        }
        total
    }

    suspend fun sizes(): Map<Category, Long> = withContext(Dispatchers.IO) {
        Category.entries.associateWith { size(it) }
    }

    suspend fun clear(category: Category) = withContext(Dispatchers.IO) {
        directories(category).forEach { emptyDirectory(it) }
        if (category == Category.TEMPORARY_FILES) {
            looseTempFiles().forEach { runCatching { it.delete() } }
        }
        if (category == Category.PROFILES) {
            // The in-memory half has to go too, or the screen keeps showing what was just
            // deleted from disk until the app is restarted.
            resetProfilesInMemory()
        }
    }

    suspend fun clearAll() {
        Category.entries.forEach { clear(it) }
    }

    /** The profile images, identity records and social lookups held in memory (iOS
     *  `resetProfilesInMemory`). This device's own saved profile record is not cache and stays. */
    private fun resetProfilesInMemory() {
        context.imageLoader.memoryCache?.clear()
        knsProfileCache.clear()
        kachatSocialImages.get().clearAll()
        kachatRegistry.get().clearProfileCache()
    }

    /**
     * Removes the CONTENTS, not the directory: services hold their directory handle from
     * construction, so deleting the folder itself would leave them writing into a path that no
     * longer exists.
     */
    private fun emptyDirectory(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    /** Recursive byte total. Skips what it cannot read: a size readout is not worth an error. */
    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkBottomUp().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    companion object {
        fun formatted(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(java.util.Locale.US, bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.US, bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(java.util.Locale.US, bytes / 1_000.0)
            else -> "$bytes bytes"
        }
    }
}
