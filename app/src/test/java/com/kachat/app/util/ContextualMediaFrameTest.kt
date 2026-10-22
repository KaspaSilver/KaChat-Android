package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/** The binary media frame read path (iOS f5f6a11, MESSAGING.md "the binary media frame"). */
class ContextualMediaFrameTest {

    /** A tiny but real-shaped JPEG: SOI, JFIF APP0, a few bytes, EOI. */
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00, 0x7F, 0x80.toByte(), 0x3E, 0x3F, 0xFF.toByte(), 0xD9.toByte()
    )

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    /** A frame exactly as iOS builds it: the header from JSONSerialization with sortedKeys,
     *  which writes "/" as "\/". */
    private fun frame(headerJson: String, raw: ByteArray, lengthOverride: Long? = null): ByteArray {
        val header = headerJson.toByteArray(Charsets.UTF_8)
        val length = lengthOverride ?: header.size.toLong()
        val lengthBytes = byteArrayOf((length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        return ContextualMediaFrame.MAGIC + lengthBytes + header + raw
    }

    /** iOS `MediaFileEnvelope.json` written out by hand - the text form an iPhone sent before. */
    private fun iosTextForm(name: String, mime: String, raw: ByteArray): String =
        "{\"type\":\"file\",\"name\":\"$name\",\"size\":${raw.size},\"mimeType\":\"$mime\"," +
            "\"content\":\"data:$mime;base64,${Base64.getEncoder().encodeToString(raw)}\"}"

    @Test
    fun `a photo frame rebuilds the iOS text form byte for byte`() {
        val f = frame("{\"mimeType\":\"image\\/jpeg\",\"name\":\"photo.jpg\",\"size\":${jpeg.size}}", jpeg)
        val expected = iosTextForm("photo.jpg", "image/jpeg", jpeg)
        assertEquals(expected, ContextualMediaFrame.envelopeText(f))
        assertEquals(
            "{\"type\":\"file\",\"name\":\"photo.jpg\",\"size\":26,\"mimeType\":\"image/jpeg\"," +
                "\"content\":\"data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAASABIAAB/gD4//9k=\"}",
            expected
        )
    }

    @Test
    fun `size is the raw byte count, not the header's`() {
        val f = frame("{\"mimeType\":\"image\\/jpeg\",\"name\":\"photo.jpg\",\"size\":999999}", jpeg)
        assertEquals(iosTextForm("photo.jpg", "image/jpeg", jpeg), ContextualMediaFrame.envelopeText(f))
    }

    @Test
    fun `name escaping matches MediaFileEnvelope - quotes and backslashes only, slash and non-ASCII as is`() {
        val f = frame("{\"mimeType\":\"audio\\/webm\",\"name\":\"caf\\u00e9 \\\"x\\\" a\\\\b\\/ü.webm\",\"size\":3}", byteArrayOf(1, 2, 3))
        assertEquals(
            "{\"type\":\"file\",\"name\":\"café \\\"x\\\" a\\\\b/ü.webm\",\"size\":3,\"mimeType\":\"audio/webm\"," +
                "\"content\":\"data:audio/webm;base64,AQID\"}",
            ContextualMediaFrame.envelopeText(f)
        )
    }

    @Test
    fun `a header without a name is named file`() {
        val f = frame("{\"mimeType\":\"audio/webm\"}", byteArrayOf(9))
        assertEquals(iosTextForm("file", "audio/webm", byteArrayOf(9)), ContextualMediaFrame.envelopeText(f))
    }

    @Test
    fun `cipher round trip - a frame decrypts to the text form, and reads as a photo and a voice note`() {
        val priv = randomScalarBytes()
        val pub = Schnorr.publicKeyXOnly(priv)
        val photo = frame("{\"mimeType\":\"image\\/jpeg\",\"name\":\"photo.jpg\",\"size\":${jpeg.size}}", jpeg)
        val opened = KasiaCipher.decrypt(KasiaCipher.encrypt(photo, pub), priv)
        assertEquals(iosTextForm("photo.jpg", "image/jpeg", jpeg), opened)
        assertNotNull(ImageMessage.parseOrNull(opened))
        assertEquals("Sent a photo", dmNotificationText(opened))

        val opus = ByteArray(300) { (it * 7).toByte() }
        val voice = frame("{\"mimeType\":\"audio\\/webm\",\"name\":\"voice.webm\",\"size\":300}", opus)
        val heard = MessageProtocol.decrypt(KasiaCipher.encrypt(voice, pub), priv)
        assertEquals(iosTextForm("voice.webm", "audio/webm", opus), heard)
        assertEquals(opus.toList(), VoiceMessage.parseOrNull(heard)!!.content.substringAfter(",").let { Base64.getDecoder().decode(it) }.toList())
        assertEquals("Sent a voice message", dmNotificationText(heard))
    }

    @Test
    fun `malformed frames are not frames`() {
        val header = "{\"mimeType\":\"image/jpeg\",\"name\":\"photo.jpg\"}"
        val good = frame(header, jpeg)
        assertNotNull(ContextualMediaFrame.envelopeText(good))
        // cut short inside the header, and at the header's end (no file bytes)
        assertNull(ContextualMediaFrame.envelopeText(good.copyOf(12)))
        assertNull(ContextualMediaFrame.envelopeText(frame(header, ByteArray(0))))
        // a header length past the end, the largest u32, and zero
        assertNull(ContextualMediaFrame.envelopeText(frame(header, jpeg, lengthOverride = (header.length + jpeg.size + 1).toLong())))
        assertNull(ContextualMediaFrame.envelopeText(frame(header, jpeg, lengthOverride = 0xFFFFFFFFL)))
        assertNull(ContextualMediaFrame.envelopeText(frame(header, jpeg, lengthOverride = 0x80000000L)))
        assertNull(ContextualMediaFrame.envelopeText(frame(header, jpeg, lengthOverride = 0)))
        // only the magic and part of the length
        assertNull(ContextualMediaFrame.envelopeText(ContextualMediaFrame.MAGIC + byteArrayOf(0, 0, 0, 1)))
        // headers that are not an object with a mimeType
        assertNull(ContextualMediaFrame.envelopeText(frame("{\"name\":\"photo.jpg\"}", jpeg)))
        assertNull(ContextualMediaFrame.envelopeText(frame("{\"mimeType\":\"\"}", jpeg)))
        assertNull(ContextualMediaFrame.envelopeText(frame("{\"mimeType\":5}", jpeg)))
        assertNull(ContextualMediaFrame.envelopeText(frame("[\"image/jpeg\"]", jpeg)))
        assertNull(ContextualMediaFrame.envelopeText(frame("{\"mimeType\":\"image/jpeg\"", jpeg)))
        // another version byte
        val v2 = good.copyOf().also { it[4] = 0x02 }
        assertNull(ContextualMediaFrame.envelopeText(v2))
    }

    @Test
    fun `a malformed frame decrypts as unreadable, never as garbage text`() {
        val priv = randomScalarBytes()
        val pub = Schnorr.publicKeyXOnly(priv)
        val header = "{\"mimeType\":\"image/jpeg\"}"
        for (bad in listOf(
            frame(header, jpeg, lengthOverride = 0xFFFFFFFFL),
            frame(header, jpeg).copyOf(11),
            frame(header, jpeg).also { it[4] = 0x02 },
        )) {
            try {
                KasiaCipher.decrypt(KasiaCipher.encrypt(bad, pub), priv)
                fail("expected an unreadable plaintext")
            } catch (e: KasiaCipher.UnreadablePlaintextException) {
                // expected
            }
        }
    }

    @Test
    fun `text is unaffected`() {
        val priv = randomScalarBytes()
        val pub = Schnorr.publicKeyXOnly(priv)
        for (text in listOf("hi", "", "Привет 👋", iosTextForm("photo.jpg", "image/jpeg", jpeg), "{\"type\":\"file\"}")) {
            assertEquals(text, KasiaCipher.decrypt(KasiaCipher.encrypt(text, pub), priv))
            assertNull(ContextualMediaFrame.envelopeText(text.toByteArray(Charsets.UTF_8)))
        }
    }

    // --- sending (iOS ContextualMediaFrame.frame(forEnvelope:), encryptContextualMessage) ---

    private fun b64(raw: ByteArray) = Base64.getEncoder().encodeToString(raw)

    @Test
    fun `a photo envelope becomes the frame iOS builds`() {
        val envelope = ImageMessage.encode("photo.jpg", jpeg.size.toLong(), b64(jpeg))
        val f = ContextualMediaFrame.frameForEnvelope(envelope)!!
        val header = "{\"mimeType\":\"image\\/jpeg\",\"name\":\"photo.jpg\",\"size\":26}"
        assertEquals(frame(header, jpeg).toList(), f.toList())
        // and it reads back as the iOS text form (not Gson's "\u003d"-escaped one)
        assertEquals(iosTextForm("photo.jpg", "image/jpeg", jpeg), ContextualMediaFrame.envelopeText(f))
    }

    @Test
    fun `a voice envelope becomes a frame and round-trips through the 1-1 cipher`() {
        val priv = randomScalarBytes()
        val pub = Schnorr.publicKeyXOnly(priv)
        val opus = ByteArray(5_000) { (it * 31).toByte() }
        val envelope = VoiceMessage.encode("voice_1.webm", opus.size.toLong(), b64(opus))
        val sealed = MessageProtocol.encryptContextual(envelope, pub)
        // raw bytes + frame header inside the cipher, not the base64 JSON
        assertTrue(sealed.ciphertext.size < opus.size + 100)
        assertEquals(iosTextForm("voice_1.webm", "audio/webm", opus), MessageProtocol.decrypt(sealed, priv))
    }

    @Test
    fun `everything else is sent as text`() {
        val priv = randomScalarBytes()
        val pub = Schnorr.publicKeyXOnly(priv)
        val notFramed = listOf(
            "hello",
            "{\"type\":\"file\"}",
            "{\"type\":\"reply\",\"content\":\"data:image/jpeg;base64,AQID\"}",
            "{\"type\":\"file\",\"name\":\"a.mp4\",\"size\":3,\"mimeType\":\"video/mp4\",\"content\":\"data:video/mp4;base64,AQID\"}",
            "{\"type\":\"file\",\"name\":\"a.pdf\",\"size\":3,\"mimeType\":\"application/pdf\",\"content\":\"data:application/pdf;base64,AQID\"}",
            "{\"type\":\"file\",\"name\":\"a.jpg\",\"size\":2,\"mimeType\":\"image/jpeg\",\"content\":\"data:image/jpeg;base64,AQI\"}",
            "{\"type\":\"file\",\"name\":\"a.jpg\",\"size\":2,\"mimeType\":\"image/jpeg\",\"content\":\"data:image/jpeg,AQID\"}",
            "{\"type\":\"file\",\"name\":\"a.jpg\",\"size\":0,\"mimeType\":\"image/jpeg\",\"content\":\"data:image/jpeg;base64,\"}",
            "{\"type\":\"file\",\"name\":\"a.jpg\",\"size\":2,\"mimeType\":\"image/jpeg\",\"content\":\"data:image/jpeg;base64,!!!!\"}",
            " {\"type\":\"file\",\"content\":\"data:image/jpeg;base64,AQID\"}",
            "https://cloud.example.com/s/abc",
        )
        for (text in notFramed) {
            assertNull(text, ContextualMediaFrame.frameForEnvelope(text))
            assertEquals(text, MessageProtocol.decrypt(MessageProtocol.encryptContextual(text, pub), priv))
        }
    }

    @Test
    fun `any image or audio mime is framed, case-insensitively, with its name kept`() {
        val envelope = "{\"type\":\"file\",\"name\":\"a/\\\"b\\\".png\",\"size\":3,\"mimeType\":\"Image/PNG\",\"content\":\"data:Image/PNG;base64,AQID\"}"
        val f = ContextualMediaFrame.frameForEnvelope(envelope)!!
        assertEquals(
            frame("{\"mimeType\":\"Image\\/PNG\",\"name\":\"a\\/\\\"b\\\".png\",\"size\":3}", byteArrayOf(1, 2, 3)).toList(),
            f.toList()
        )
        assertEquals(
            "{\"type\":\"file\",\"name\":\"a/\\\"b\\\".png\",\"size\":3,\"mimeType\":\"Image/PNG\",\"content\":\"data:Image/PNG;base64,AQID\"}",
            ContextualMediaFrame.envelopeText(f)
        )
    }

    @Test
    fun `the photo fee preview prices the single encoding`() {
        assertEquals(26_946, ImagePrep.estimatedWirePayloadSize(20_000))
        assertEquals(42_946, ImagePrep.estimatedWirePayloadSize(32_000))
    }

    @Test
    fun `a 1-1 voice note stops at 31 KB, within one transaction`() {
        val cap = com.kachat.app.services.VoiceRecorderService.MAX_DIRECT_ON_CHAIN_VOICE_BYTES
        val fullNote = com.kachat.app.services.VoiceRecorderService.MAX_DIRECT_ON_CHAIN_RECORDING_DURATION_MS
        // iOS's own WebM layout (~1.06 KB a second) holds the full 25 s ...
        assertTrue(VoiceMessage.estimatedRecordingBytes(fullNote) < cap)
        // ... MediaRecorder's (~1.6 KB a second, the fallback) reaches 31 KB before 25 s
        assertTrue(VoiceMessage.estimatedRecordingBytes(fullNote, ownWebM = false) > cap)
        assertTrue(VoiceMessage.estimatedRecordingBytes(10_000, ownWebM = false) < cap)
        // ... and a full note's payload stays under the ~43 KB a 32 KB photo takes
        assertTrue(((cap + 150) * 4.0 / 3.0).toInt() + 80 < ImagePrep.estimatedWirePayloadSize(ImagePrep.HIGH_QUALITY_CHAT_TARGET_BYTES))
    }
}
