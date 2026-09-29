package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeExternalLinkTest {
    @Test
    fun `web and email open`() {
        assertEquals("https://kachat.app", SafeExternalLink.forProfileField("https://kachat.app"))
        assertEquals("http://x.com/a", SafeExternalLink.forProfileField("http://x.com/a"))
        assertEquals("https://x.com/kachat", SafeExternalLink.forProfileField("x.com/kachat"))
        assertEquals("mailto:me@kachat.app", SafeExternalLink.forProfileField("me@kachat.app"))
        assertEquals("mailto:me@kachat.app", SafeExternalLink.forProfileField("mailto:me@kachat.app"))
        assertEquals("https://example.com:8080/x", SafeExternalLink.forProfileField("example.com:8080/x"))
    }

    @Test
    fun `other schemes are refused`() {
        assertNull(SafeExternalLink.forProfileField("httpx://evil"))
        assertNull(SafeExternalLink.forProfileField("intent://scan#Intent;scheme=zxing;end"))
        assertNull(SafeExternalLink.forProfileField("javascript:alert(1)"))
        assertNull(SafeExternalLink.forProfileField("kaspa:qqexample"))
        assertNull(SafeExternalLink.forProfileField("  "))
    }
}
