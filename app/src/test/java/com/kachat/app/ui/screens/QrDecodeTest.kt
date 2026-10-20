package com.kachat.app.ui.screens

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The camera scanner's frame decoding: a luminance (Y) plane whose rows may be padded past the
 * frame's width, as many phones deliver them.
 */
class QrDecodeTest {

    private val address = "kaspa:qrvndg556wq7th4g879reunzp69wjrft6pqxwv8n7an5xdd0sfaj5x4n9td5m"

    /** [text] as a QR code drawn into a [width] x [height] frame whose rows are [stride] bytes,
     *  dark modules on a light background (or the reverse when [inverted]). */
    private fun frame(text: String, width: Int, height: Int, stride: Int, inverted: Boolean = false): ByteArray {
        val side = minOf(width, height) * 3 / 4
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, mapOf(EncodeHintType.MARGIN to 2))
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        val dark: Byte = if (inverted) 230.toByte() else 20
        val light: Byte = if (inverted) 20 else 230.toByte()
        // A real plane: every row padded to [stride], the last row only [width] long.
        val data = ByteArray(stride * (height - 1) + width) { light }
        for (y in 0 until height) for (x in 0 until width) {
            val inCode = x - left in 0 until matrix.width && y - top in 0 until matrix.height
            if (inCode && matrix[x - left, y - top]) data[y * stride + x] = dark
            // The padding past [width] holds junk on a real device.
            if (x == width - 1 && y < height - 1) for (p in width until stride) data[y * stride + p] = (p * 37).toByte()
        }
        return data
    }

    @Test
    fun `a frame whose rows are exactly its width decodes`() {
        val data = frame(address, 640, 480, 640)
        assertEquals(address, decodeQrLuminance(data, 640, 480, 640)?.text)
    }

    @Test
    fun `a frame whose rows are padded past its width decodes`() {
        val data = frame(address, 640, 480, 704)
        assertEquals(address, decodeQrLuminance(data, 640, 480, 704)?.text)
    }

    @Test
    fun `reading padded rows as if they were the frame's width misses the code`() {
        // What the scanner did before: the rows drift by the padding, and nothing decodes.
        val data = frame(address, 640, 480, 704)
        val skewed = PlanarYUVLuminanceSource(data, 640, 480, 0, 0, 640, 480, false)
        val result = try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(skewed)))
        } catch (e: NotFoundException) {
            null
        }
        assertNull(result)
    }

    @Test
    fun `a light-on-dark code decodes`() {
        val data = frame(address, 640, 480, 640, inverted = true)
        assertEquals(address, decodeQrLuminance(data, 640, 480, 640)?.text)
    }

    @Test
    fun `a frame with no code is null`() {
        val data = ByteArray(640 * 480) { 128.toByte() }
        assertNull(decodeQrLuminance(data, 640, 480, 640))
    }

    @Test
    fun `a 1280 x 720 frame with a 1344 stride decodes`() {
        val data = frame(address, 1280, 720, 1344)
        assertEquals(address, decodeQrLuminance(data, 1280, 720, 1344)?.text)
    }
}
