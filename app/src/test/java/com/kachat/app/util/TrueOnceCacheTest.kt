package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account-store check's cache: a yes is kept for the process, a no is asked again. */
class TrueOnceCacheTest {

    @Test
    fun `a yes is kept and the check is not asked again`() {
        val cache = TrueOnceCache()
        var asked = 0
        assertTrue(cache.get { asked++; true })
        assertTrue(cache.get { asked++; false })
        assertEquals(1, asked)
    }

    @Test
    fun `a no is asked again, so a store that recovers is noticed`() {
        val cache = TrueOnceCache()
        var asked = 0
        assertFalse(cache.get { asked++; false })
        assertFalse(cache.get { asked++; false })
        assertTrue(cache.get { asked++; true })
        assertTrue(cache.get { asked++; false })
        assertEquals(3, asked)
    }
}
