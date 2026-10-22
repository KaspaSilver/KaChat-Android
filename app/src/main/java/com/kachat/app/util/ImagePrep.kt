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

    /** The on-chain photo budget for [prepareForChatMessage]. 20 KB since the binary media frame
     *  ([ContextualMediaFrame]) took one base64 layer off: a 20 KB photo now costs what a 15 KB one
     *  did (~27 KB of payload) - iOS `defaultChatTargetBytes`. */
    const val DEFAULT_CHAT_TARGET_BYTES = 20_000
    /** Settings > Chats > Higher quality photos: the largest photo one transaction carries with
     *  margin - ~43 KB of payload, ~87,000 of the 100,000 mass a standard transaction may have at
     *  2 grams per byte (iOS `highQualityChatTargetBytes`). */
    const val HIGH_QUALITY_CHAT_TARGET_BYTES = 32_000

    /**
     * Rough estimate of the final wire payload size for a picked-but-not-yet-compressed 1:1
     * photo, used only for the live "fee so far" preview - the real send always measures actual
     * bytes. The binary media frame carries the raw bytes; only the payload's own base64 remains
     * (frame header + cipher overhead ~150 bytes, then 4/3) - iOS `estimatedWirePayloadSize`.
     */
    fun estimatedWirePayloadSize(targetBytes: Int = DEFAULT_CHAT_TARGET_BYTES): Int =
        ((targetBytes + 150) * 4.0 / 3.0).toInt() + 80

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
