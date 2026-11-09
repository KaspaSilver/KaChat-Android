package com.kachat.app.services

import com.kachat.app.util.KaspaAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaPostPreviewLoaderTest {

    @Test
    fun `post text becomes one paragraph without control or direction marks`() {
        val raw = "  Hello\nworld\r\nagain​‮flip⁦﻿\u0007 done  "
        assertEquals("Hello world  againflip done", KaPostPreviewLoader.cleanText(raw))
    }

    @Test
    fun `long post text is cut to 300 characters with an ellipsis`() {
        val cleaned = KaPostPreviewLoader.cleanText("a".repeat(400))
        assertEquals("a".repeat(300) + "…", cleaned)
        assertEquals("short", KaPostPreviewLoader.cleanText("short"))
    }

    @Test
    fun `a compressed poster key reads as the address made of it`() {
        val xOnly = ByteArray(32) { (it + 1).toByte() }
        val hex = "02" + xOnly.joinToString("") { "%02x".format(it) }
        val address = KaPostPreviewLoader.authorAddress(hex)
        assertTrue(address != null && address.startsWith("kaspa:"))
        val (version, payload) = KaspaAddress.decode(address!!)
        assertEquals(0, version.toInt())
        assertArrayEquals(xOnly, payload)
    }

    @Test
    fun `a poster key that is not 33 bytes of hex reads as no one`() {
        assertNull(KaPostPreviewLoader.authorAddress("02" + "0".repeat(62)))
        assertNull(KaPostPreviewLoader.authorAddress("zz" + "0".repeat(64)))
        assertNull(KaPostPreviewLoader.authorAddress(""))
    }
}
