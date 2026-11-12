package com.kachat.app.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/** Prepares a picked image for KNS profile upload — matches iOS's client-side prep exactly (downscale to a 1400px longest edge, always re-encode as PNG regardless of source format). */
object ImagePrep {
    private const val MAX_DIMENSION = 1400

    fun prepareForUpload(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver

        // Downsample during decode rather than decoding at full resolution first — a modern 12MP+
        // camera photo decoded straight to ARGB_8888 can be 40-50MB and OOM before any scaling
        // happens (same reasoning as prepareForChatMessage below). Read just the bounds, pick an
        // inSampleSize that lands at or just above MAX_DIMENSION, then decode that.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw IllegalStateException("Could not read image"))
            .use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_DIMENSION || bounds.outHeight / (sampleSize * 2) >= MAX_DIMENSION) {
            sampleSize *= 2
        }

        val original = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw IllegalStateException("Could not read image")

        // inSampleSize only halves, so the result can still overshoot — clamp exactly to 1400px.
        val scale = MAX_DIMENSION.toFloat() / maxOf(original.width, original.height)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            original
        }

        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** The default budget of [prepareForChatMessage] (small images only; chat photos go by
     *  [PhotoTier], iOS 7c5c22e3 `defaultChatTargetBytes`). */
    const val DEFAULT_CHAT_TARGET_BYTES = 20_000

    // MARK: - Quality tiers (on-chain chat photos, iOS 7c5c22e3)

    /** Long edges tried for a tier, largest first: a big budget buys a big image at a decent
     *  quality, not a small one at quality 1 (iOS `tierEdges`). */
    val TIER_EDGES = intArrayOf(3000, 2560, 2240, 2048, 1920, 1600, 1440, 1280, 1080, 960, 800, 640, 480, 360)
    /** The JPEG quality range searched at each edge (iOS 0.5...0.85). */
    private const val TIER_MAX_QUALITY = 85
    private const val TIER_MIN_QUALITY = 50

    /**
     * The best-looking JPEG within each of [budgets] bytes, by budget (iOS
     * `prepareJPEGForTiers`): the LARGEST long edge (never upscaled) at which quality 0.5 fits,
     * then the highest quality up to 0.85 that still fits there. Only when nothing fits at 0.5
     * even at the smallest edge does the quality go lower. The image is re-rendered into a fresh
     * bitmap, orientation applied, so none of the original's metadata (EXIF, GPS, camera) is
     * carried. The quality-0.5 size measured at one edge predicts the others (size ~ edge
     * squared), so a small budget skips the big edges. Heavy: call it off the main thread.
     */
    fun prepareJpegForTiers(context: Context, uri: Uri, budgets: Collection<Int>): Map<Int, ByteArray> {
        val original = decodeOriented(context, uri, TIER_EDGES[0])
        val originalEdge = maxOf(original.width, original.height)
        val edges = ArrayList<Int>()
        edges.add(minOf(originalEdge, TIER_EDGES[0]))
        TIER_EDGES.filter { it < originalEdge && it != edges[0] }.forEach { edges.add(it) }

        var renderedEdge = -1
        var rendered: Bitmap? = null
        fun render(edge: Int): Bitmap {
            rendered?.let { if (renderedEdge == edge) return it }
            val scale = edge.toFloat() / originalEdge
            val scaled = if (edge >= originalEdge) original else Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true
            )
            rendered?.let { if (it !== original && it !== scaled) it.recycle() }
            renderedEdge = edge
            rendered = scaled
            return scaled
        }
        val minQualitySize = HashMap<Int, Int>()
        fun atMinQuality(edge: Int): ByteArray {
            val data = jpeg(render(edge), TIER_MIN_QUALITY)
            minQualitySize[edge] = data.size
            return data
        }
        fun predicted(edge: Int): Int? {
            val known = minQualitySize.maxByOrNull { it.key } ?: return null
            return (known.value.toDouble() * edge * edge / (known.key.toDouble() * known.key)).toInt()
        }

        val results = HashMap<Int, ByteArray>()
        for (budget in budgets.toSortedSet().reversed()) {
            var chosen: ByteArray? = null
            for (edge in edges) {
                val known = minQualitySize[edge]
                if (known != null && known > budget) continue
                if (known == null) {
                    val guess = predicted(edge)
                    if (guess != null && guess > budget * 13 / 10) continue
                }
                val floor = atMinQuality(edge)
                if (floor.size > budget) continue
                // Fits at the floor: the best quality that still fits, at this edge.
                var best = floor
                var low = TIER_MIN_QUALITY + 1
                var high = TIER_MAX_QUALITY
                while (low <= high) {
                    val mid = (low + high) / 2
                    val data = jpeg(render(edge), mid)
                    if (data.size <= budget) {
                        best = data
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                chosen = best
                break
            }
            if (chosen == null) {
                // Nothing fits at the quality floor: the smallest edge, below the floor.
                chosen = compressToQualityBudget(render(edges.last()), budget)
            }
            results[budget] = chosen
        }
        return results
    }

    /** The tier's photo on a path - one JPEG within its byte budget (iOS `prepareForTier`). */
    fun prepareForTier(context: Context, uri: Uri, tier: PhotoTier, forGroup: Boolean): PreparedChatImage {
        val budget = tier.maxBytes(forGroup)
        val data = prepareJpegForTiers(context, uri, listOf(budget))[budget] ?: throw IllegalStateException("Could not compress the photo")
        return PreparedChatImage(data, "photo.jpg", "image/jpeg")
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, this) }.toByteArray()

    /** [uri] decoded with its EXIF orientation applied, its long edge at most about [maxEdge]
     *  (ImageDecoder scales exactly; the BitmapFactory fallback only halves). */
    private fun decodeOriented(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = minOf(1f, maxEdge.toFloat() / maxOf(w, h))
                    if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        }
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw IllegalStateException("Could not read image")).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxEdge || bounds.outHeight / (sampleSize * 2) >= maxEdge) sampleSize *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw IllegalStateException("Could not read image")
    }

    private const val CHAT_MAX_DIMENSION = 1280
    private const val MAX_SHRINK_ATTEMPTS = 4

    /** A compressed chat photo ready to embed in an outgoing message — mirrors iOS's `PreparedChatImage` (`ImagePrep.swift`). */
    data class PreparedChatImage(val bytes: ByteArray, val fileName: String, val mimeType: String)

    /**
     * Compresses a picked image to fit directly in an on-chain chat message payload, targeting
     * [targetBytes] of encoded data. Downsamples during decode via [BitmapFactory.Options.inSampleSize]
     * rather than decoding at full resolution first — a modern 12MP+ camera photo decoded straight to
     * ARGB_8888 can be 40-50MB and OOM before any scaling ever happens.
     *
     * Always JPEG. AVIF was tried here previously (and on iOS) for smaller on-chain payloads, but
     * was removed: AVIF *decode* support is inconsistent across Android devices/OS builds even on
     * ones with a working AV1 *encoder*, so a photo sent from either platform could permanently
     * fail to render for some recipients with no way to detect or recover from that in advance.
     * JPEG decodes everywhere, on every device on both platforms.
     */
    fun prepareForChatMessage(context: Context, uri: Uri, targetBytes: Int = DEFAULT_CHAT_TARGET_BYTES): PreparedChatImage {
        val resolver = context.contentResolver

        // BitmapFactory.decodeStream() always returns null when inJustDecodeBounds is set (it only
        // populates outWidth/outHeight) — checking openInputStream's own nullability separately,
        // rather than chaining ?: off the decode result, since the decode result is null on success too.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: throw IllegalStateException("Could not read image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= CHAT_MAX_DIMENSION || bounds.outHeight / (sampleSize * 2) >= CHAT_MAX_DIMENSION) {
            sampleSize *= 2
        }

        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw IllegalStateException("Could not read image")

        // inSampleSize only halves, so the result can still overshoot the target — clamp exactly.
        val scale = CHAT_MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }

        var jpegBitmap = bitmap
        repeat(MAX_SHRINK_ATTEMPTS) { attempt ->
            val compressed = compressToQualityBudget(jpegBitmap, targetBytes)
            if (compressed.size <= targetBytes || attempt == MAX_SHRINK_ATTEMPTS - 1) {
                return PreparedChatImage(compressed, "photo.jpg", "image/jpeg")
            }
            jpegBitmap = Bitmap.createScaledBitmap(jpegBitmap, (jpegBitmap.width * 0.7f).toInt().coerceAtLeast(1), (jpegBitmap.height * 0.7f).toInt().coerceAtLeast(1), true)
        }
        error("unreachable") // repeat() above always returns before falling through
    }

    /**
     * Saves already-decoded photo-message bytes to the device's shared Pictures gallery
     * (Pictures/KaChat), via MediaStore rather than direct file access. On API 29+ this needs no
     * permission at all (scoped storage); on API 26-28 the caller must already hold
     * WRITE_EXTERNAL_STORAGE (see AndroidManifest's maxSdkVersion=28 entry — MediaStore inserts
     * still enforce it pre-scoped-storage). Returns false on any failure rather than throwing,
     * since the caller only needs a success/failure toast, not a specific reason.
     */
    fun saveToGallery(context: Context, bytes: ByteArray, fileName: String, mimeType: String = "image/jpeg"): Boolean {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/KaChat")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Binary-searches JPEG quality [5, 95] for the highest quality whose encoded size still fits [targetBytes]. */
    private fun compressToQualityBudget(bitmap: Bitmap, targetBytes: Int): ByteArray {
        var low = 5
        var high = 95
        var best = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, low, this) }.toByteArray()
        while (low <= high) {
            val mid = (low + high) / 2
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, mid, out)
            val bytes = out.toByteArray()
            if (bytes.size <= targetBytes) {
                best = bytes
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}
