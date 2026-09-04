package com.kachat.app.services

import android.content.Context
import coil.imageLoader
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
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Category(val title: String, val detail: String) {
        IMAGES(
            "Images",
            "Avatars, banners and link preview pictures. Downloaded again when you next see them."
        ),
        LINK_PREVIEWS(
            "Link Previews",
            "Thumbnails fetched for shared links. Rebuilt the next time a link is shown."
        ),
        TEMPORARY_FILES(
            "Temporary Files",
            "Scratch files from sending photos, voice notes and exports. Safe to remove at any time."
        ),
    }

    /** Coil owns its own directory; the rest are ours by name. */
    private fun directories(category: Category): List<File> = when (category) {
        Category.IMAGES -> listOfNotNull(context.imageLoader.diskCache?.directory?.toFile())
        Category.LINK_PREVIEWS -> listOf(File(context.cacheDir, "nextcloud_previews"))
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
        if (category == Category.IMAGES) {
            // The in-memory half has to go too, or the screen keeps showing what was just
            // deleted from disk until the app is restarted.
            context.imageLoader.memoryCache?.clear()
        }
    }

    suspend fun clearAll() {
        Category.entries.forEach { clear(it) }
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
