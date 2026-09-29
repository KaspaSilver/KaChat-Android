package com.kachat.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build

/**
 * Decodes image bytes that came from someone else - a chat photo, a group photo, a backup
 * avatar - without trusting the size the image claims. A JPEG of a few kilobytes can declare
 * 30,000 x 30,000 pixels; decoded as-is that is a multi-gigabyte bitmap and an out-of-memory
 * crash every time the chat holding it opens. The header is read first, and the decode is
 * sampled down so neither side exceeds [maxDimension]. KaChat's own photos are sent at most
 * 1400px (ImagePrep), so a real photo is never touched by the default cap.
 */
object SafeBitmapDecode {
    const val DEFAULT_MAX_DIMENSION = 2048

    fun decode(bytes: ByteArray, maxDimension: Int = DEFAULT_MAX_DIMENSION): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width > 0 && height > 0) {
            var sample = 1
            while (width / sample > maxDimension || height / sample > maxDimension) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { return it }
        }
        // ImageDecoder has decoded what BitmapFactory could not on some OEM builds; it gets the
        // same cap through its target size.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val largest = maxOf(w, h)
                if (largest > maxDimension && largest > 0) {
                    val scale = maxDimension.toDouble() / largest
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
