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

    // --- part tags (iOS 7c5c22e3, MESSAGING.md "Long voice notes" / "Photos: quality tiers") ---

    private val noteId = "0123456789abcdef0123456789abcdef"

    @Test
    fun `a voice part's tag rides in the header, sorted as iOS writes it`() {
        val opus = ByteArray(300) { (it * 13).toByte() }
        val tag = VoicePartTag.of(noteId, 2, 12)!!
        val envelope = ContextualMediaFrame.mediaFileJson(tag.fileName, opus.size, "audio/webm", "data:audio/webm;base64,${b64(opus)}", voicePart = tag)
        // The tag sits between mimeType and content in the text form.
        assertEquals(
            "{\"type\":\"file\",\"name\":\"voice-2of12.webm\",\"size\":300,\"mimeType\":\"audio/webm\"," +
                "\"voicePart\":{\"id\":\"$noteId\",\"index\":2,\"count\":12},\"content\":\"data:audio/webm;base64,${b64(opus)}\"}",
            envelope
        )
        val f = ContextualMediaFrame.frameForEnvelope(envelope)!!
        val header = "{\"mimeType\":\"audio\\/webm\",\"name\":\"voice-2of12.webm\",\"size\":300," +
            "\"voicePart\":{\"count\":12,\"id\":\"$noteId\",\"index\":2}}"
        assertEquals(frame(header, opus).toList(), f.toList())
        assertEquals(envelope, ContextualMediaFrame.envelopeText(f))
        assertEquals(tag, VoicePartTag.sniff(ContextualMediaFrame.envelopeText(f)))
    }

    @Test
    fun `a photo chunk's tag is mediaPart, first in the sorted header`() {
        val tag = VoicePartTag.of(noteId, 1, 2)!!
        val envelope = ContextualMediaFrame.mediaFileJson(tag.photoFileName, jpeg.size, "image/jpeg", "data:image/jpeg;base64,${b64(jpeg)}", mediaPart = tag)
        val f = ContextualMediaFrame.frameForEnvelope(envelope)!!
        val header = "{\"mediaPart\":{\"count\":2,\"id\":\"$noteId\",\"index\":1},\"mimeType\":\"image\\/jpeg\"," +
            "\"name\":\"photo-1of2.jpg\",\"size\":26}"
        assertEquals(frame(header, jpeg).toList(), f.toList())
        assertEquals(envelope, ContextualMediaFrame.envelopeText(f))
    }

    @Test
    fun `a malformed or misplaced tag is dropped - a plain single note or photo`() {
        val opus = byteArrayOf(1, 2, 3)
        val bad = listOf(
            "{\"count\":13,\"id\":\"$noteId\",\"index\":2}",
            "{\"count\":2,\"id\":\"$noteId\",\"index\":0}",
            "{\"count\":2,\"id\":\"$noteId\",\"index\":3}",
            "{\"count\":2,\"id\":\"0123\",\"index\":1}",
            "{\"count\":2,\"id\":\"zz23456789abcdef\",\"index\":1}",
            "{\"count\":\"2\",\"id\":\"$noteId\",\"index\":1}",
            "{\"count\":2.5,\"id\":\"$noteId\",\"index\":1}",
            "{\"count\":true,\"id\":\"$noteId\",\"index\":1}",
        )
        for (tag in bad) {
            val f = frame("{\"mimeType\":\"audio\\/webm\",\"name\":\"v.webm\",\"size\":3,\"voicePart\":$tag}", opus)
            assertEquals(tag, iosTextForm("v.webm", "audio/webm", opus), ContextualMediaFrame.envelopeText(f))
        }
        // The voice key on a photo is no tag either.
        val onPhoto = frame("{\"mimeType\":\"image\\/jpeg\",\"name\":\"p.jpg\",\"size\":26,\"voicePart\":{\"count\":2,\"id\":\"$noteId\",\"index\":1}}", jpeg)
        assertEquals(iosTextForm("p.jpg", "image/jpeg", jpeg), ContextualMediaFrame.envelopeText(onPhoto))
    }

    @Test
    fun `a group voice note is sealed as the frame and read back as the text form`() {
        val opus = ByteArray(2_000) { (it * 7).toByte() }
        val tag = VoicePartTag.of(noteId, 1, 3)!!
        val envelope = ContextualMediaFrame.mediaFileJson(tag.fileName, opus.size, "audio/webm", "data:audio/webm;base64,${b64(opus)}", voicePart = tag)
        val plaintext = GroupCipher.groupPlaintextData(envelope)
        assertEquals(ContextualMediaFrame.MAGIC.toList(), plaintext.take(5))
        val random = SecureRandom()
        val root = ByteArray(32).also { random.nextBytes(it) }
        val groupId = ByteArray(32).also { random.nextBytes(it) }
        val senderId = ByteArray(32).also { random.nextBytes(it) }
        val msgId = ByteArray(24).also { random.nextBytes(it) }
        val sealed = GroupCipher.encryptMessage(plaintext, root, groupId, 3L, senderId, msgId)
        // raw bytes + header inside the cipher, not 4/3 of base64
        assertTrue(sealed.size < opus.size + 250)
        assertEquals(envelope, GroupCipher.decryptMessage(sealed, root, groupId, 3L, senderId, msgId))
        // Text and old text-form group notes stay UTF-8.
        assertEquals("hello".toByteArray().toList(), GroupCipher.groupPlaintextData("hello").toList())
        val textForm = iosTextForm("v.webm", "video/mp4", opus)
        assertEquals(textForm.toByteArray().toList(), GroupCipher.groupPlaintextData(textForm).toList())
        val legacy = GroupCipher.encryptMessage(iosTextForm("v.webm", "audio/webm", opus), root, groupId, 3L, senderId, msgId)
        assertEquals(iosTextForm("v.webm", "audio/webm", opus), GroupCipher.decryptMessage(legacy, root, groupId, 3L, senderId, msgId))
    }

    @Test
    fun `a malformed group frame is unreadable, never garbage text`() {
        val random = SecureRandom()
        val root = ByteArray(32).also { random.nextBytes(it) }
        val groupId = ByteArray(32).also { random.nextBytes(it) }
        val senderId = ByteArray(32).also { random.nextBytes(it) }
        val msgId = ByteArray(24).also { random.nextBytes(it) }
        val sealed = GroupCipher.encryptMessage(byteArrayOf(0xFF.toByte(), 1, 2, 3), root, groupId, 1L, senderId, msgId)
        assertNull(GroupCipher.decryptMessage(sealed, root, groupId, 1L, senderId, msgId))
    }
}
